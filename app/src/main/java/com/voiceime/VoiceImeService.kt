package com.voiceime

import android.Manifest
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Voice-only input method: mic tap → record → LLM transcribe → clipboard + commitText.
 * The input view is inflated once in onCreateInputView; per-field refresh in onStartInputView.
 */
class VoiceImeService : InputMethodService() {

    private enum class State { IDLE, RECORDING, UPLOADING, ERROR }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    companion object {
        /** Max history previews shown inline in the IME strip. */
        const val CLIP_STRIP_LIMIT = 8

        /** Label for the Enter button from the editor's action id. */
        internal fun enterLabel(actionId: Int): String = when (actionId and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_GO -> "Go"
            EditorInfo.IME_ACTION_SEARCH -> "Search"
            EditorInfo.IME_ACTION_SEND -> "Send"
            EditorInfo.IME_ACTION_NEXT -> "Next"
            EditorInfo.IME_ACTION_DONE -> "Done"
            EditorInfo.IME_ACTION_PREVIOUS -> "Previous"
            else -> "Enter"
        }

        /**
         * Formats elapsed seconds as m:ss without String.format's regex/locale
         * cost on every UI tick.
         */
        internal fun formatElapsed(totalSeconds: Int): String {
            val seconds = totalSeconds.coerceAtLeast(0)
            val minutes = seconds / 60
            val remainder = seconds - minutes * 60
            return buildString(5) {
                append(minutes)
                append(':')
                if (remainder < 10) append('0')
                append(remainder)
            }
        }
    }

    private var rootView: View? = null
    /** Mic control: FAB (ImageButton) in the normal view, plain Button in fallback. */
    private var micButton: View? = null
    private var statusText: TextView? = null
    /** True when the input view is the programmatic minimal fallback (inflate failed). */
    private var fallbackMode = false
    private var timerText: TextView? = null
    private var waveView: WaveformView? = null
    private var statusDot: View? = null
    private var dotIdle: ColorStateList? = null
    private var dotRecording: ColorStateList? = null
    private var dotUploading: ColorStateList? = null
    private var dotError: ColorStateList? = null
    private var stopButton: Button? = null
    private var cancelButton: Button? = null
    private var retryButton: Button? = null
    private var settingsButton: Button? = null
    private var enterButton: Button? = null
    private var lastEditorInfo: EditorInfo? = null
    private var clipboardStore: ClipboardStore? = null
    private var clipStripScroll: HorizontalScrollView? = null
    private var clipStrip: LinearLayout? = null
    private var clipMoreButton: Button? = null
    private var clipEntries: List<ClipboardEntry> = emptyList()
    private var clipListener: ClipboardManager.OnPrimaryClipChangedListener? = null
    private var lastClipFingerprint: Int = 0
    private var clipPopup: PopupWindow? = null

    private var state: State = State.IDLE
    private var recorder: AudioRecorder? = null
    private var transcribeJob: Job? = null
    private var timerRunnable: Runnable? = null
    private var recordStartMs: Long = 0L
    private var lastTimerSecond: Int = -1
    private var lastEnterAction: Int = Int.MIN_VALUE
    private var actionsRow: View? = null
    private var capRunnable: Runnable? = null
    private var blockedHint: String? = null
    private var blockedHintShown: Boolean = false
    private var lastWav: ByteArray? = null
    private var passwordArmed = false
    private var passwordField = false
    private var keyCursor: Int = 0
    /** Set when mic was tapped without permission; cleared on resume or new tap. */
    private var pendingMicResume = false
    /** Manual-retry backoff: 0→immediate, then 2s, 4s, capped at 8s. Reset on success. */
    private var retryBackoffMs: Long = 0L

    private lateinit var settings: SettingsStore
    private val llm = LlmClient()

    override fun onCreate() {
        super.onCreate()
        settings = SettingsStore(this)
    }

    override fun onCreateInputView(): View {
        // System may re-add the cached view on configuration change
        // (resetStateForNewConfiguration → setInputView); a view that
        // still has a parent crashes with "child already has a parent".
        rootView?.let {
            (it.parent as? ViewGroup)?.removeView(it)
            return it
        }
        return try {
            val view = inflateInputView()
            fallbackMode = false
            Log.e("VoiceIme", "input view ready state=$state")
            view
        } catch (t: Throwable) {
            Log.e("VoiceIme", "input view inflate failed, using fallback", t)
            fallbackMode = true
            buildFallbackView()
        }
    }

    private fun inflateInputView(): View {
        val view = layoutInflater.inflate(R.layout.ime_voice_view, null)
        micButton = view.findViewById(R.id.imeMic)
        statusText = view.findViewById(R.id.imeStatus)
        timerText = view.findViewById(R.id.imeTimer)
        waveView = view.findViewById(R.id.imeWave)
        statusDot = view.findViewById(R.id.imeStatusDot)
        val attrs = intArrayOf(
            com.google.android.material.R.attr.colorOutlineVariant,
            com.google.android.material.R.attr.colorPrimary,
            com.google.android.material.R.attr.colorTertiary,
            com.google.android.material.R.attr.colorError,
        )
        val ta = obtainStyledAttributes(attrs)
        try {
            dotIdle = ColorStateList.valueOf(ta.getColor(0, 0xFF808080.toInt()))
            dotRecording = ColorStateList.valueOf(ta.getColor(1, 0xFF808080.toInt()))
            dotUploading = ColorStateList.valueOf(ta.getColor(2, 0xFF808080.toInt()))
            dotError = ColorStateList.valueOf(ta.getColor(3, 0xFF808080.toInt()))
        } finally {
            ta.recycle()
        }
        stopButton = view.findViewById(R.id.imeStop)
        cancelButton = view.findViewById(R.id.imeCancel)
        retryButton = view.findViewById(R.id.imeRetry)
        settingsButton = view.findViewById(R.id.imeSettings)
        enterButton = view.findViewById(R.id.imeEnter)
        actionsRow = view.findViewById(R.id.imeActionsRow)
        clipStripScroll = view.findViewById(R.id.imeClipStripScroll)
        clipStrip = view.findViewById(R.id.imeClipStrip)
        clipMoreButton = view.findViewById(R.id.imeClipMore)
        clipMoreButton?.setOnClickListener { showClipPopup() }

        micButton?.setOnClickListener { onMicTapped() }
        stopButton?.setOnClickListener { finishRecording(upload = true) }
        cancelButton?.setOnClickListener { onCancelTapped() }
        retryButton?.setOnClickListener { retryLast() }
        settingsButton?.setOnClickListener { openSettings() }
        enterButton?.setOnClickListener { onEnterTapped() }

        rootView = view
        renderState()
        return view
    }

    /**
     * Minimal programmatic input view used when XML inflation throws (custom
     * views, M3 styles, theme attrs can all fail per-device). Framework widgets
     * only; reuses imeMic/imeStatus ids so existing listeners and renderState
     * keep working. All other view fields are nulled so renderState's ?.
     * logic skips them.
     */
    private fun buildFallbackView(): View {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val mic = Button(this).apply {
            id = R.id.imeMic
            text = getString(R.string.ime_mic_description)
            setOnClickListener { onMicTapped() }
        }
        val status = TextView(this).apply {
            id = R.id.imeStatus
            setText(R.string.ime_status_idle)
        }
        layout.addView(mic)
        layout.addView(status)
        micButton = mic
        statusText = status
        timerText = null
        waveView = null
        statusDot = null
        dotIdle = null
        dotRecording = null
        dotUploading = null
        dotError = null
        stopButton = null
        cancelButton = null
        retryButton = null
        settingsButton = null
        enterButton = null
        actionsRow = null
        clipStripScroll = null
        clipStrip = null
        clipMoreButton = null
        rootView = layout
        renderState()
        return layout
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        lastEditorInfo = info
        passwordField = isPasswordField(info)
        passwordArmed = false
        Log.e("VoiceIme", "start input state=$state fallback=$fallbackMode restarting=$restarting")
        applyClipPrefs()
        refreshClipStrip()
        registerClipListener()
        renderState()
    }

    override fun onWindowShown() {
        super.onWindowShown()
        registerClipListener()
        // Permission screen (NEW_TASK activity) covers the IME; when it
        // returns, the window shows again — resume the pending mic tap.
        if (pendingMicResume && state == State.IDLE) {
            pendingMicResume = false
            onMicTapped()
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        lastEditorInfo = null
        unregisterClipListener()
        cancelWork()
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        dismissClipPopup()
        unregisterClipListener()
        cancelWork()
    }

    override fun onDestroy() {
        dismissClipPopup()
        unregisterClipListener()
        cancelWork()
        serviceScope.cancel()
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun hapticTap() {
        try {
            (rootView ?: return).performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        } catch (e: Exception) {
            // Haptics are best-effort.
        }
    }

    private fun hapticConfirm() {
        try {
            val view = rootView ?: return
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            } else {
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            }
        } catch (e: Exception) {
            // Haptics are best-effort.
        }
    }

    private fun onMicTapped() {
        if (state == State.RECORDING) {
            finishRecording(upload = true)
            return
        }
        if (passwordField && !passwordArmed) {
            passwordArmed = true
            statusText?.setText(R.string.ime_password_confirm)
            return
        }
        passwordArmed = false
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            val intent = Intent(this, MicPermissionActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            // Auto-resume: when the permission screen returns and the grant
            // is in place, start recording without a second mic tap.
            pendingMicResume = true
            return
        }
        pendingMicResume = false
        if (settings.apiKeys.isEmpty()) {
            showError(getString(R.string.ime_error_no_key))
            return
        }
        startRecording()
        if (state == State.RECORDING) hapticTap()
    }

    private fun onEnterTapped() {
        val info = lastEditorInfo
        val actionId = info?.imeOptions?.and(EditorInfo.IME_MASK_ACTION)
            ?: EditorInfo.IME_ACTION_UNSPECIFIED
        val ic = currentInputConnection ?: return
        if (actionId == EditorInfo.IME_ACTION_UNSPECIFIED || actionId == EditorInfo.IME_ACTION_NONE) {
            try {
                ic.beginBatchEdit()
                try {
                    ic.commitText("\n", 1)
                } finally {
                    ic.endBatchEdit()
                }
            } catch (e: Exception) {
                // No connection or commit failed; nothing to fall back to.
            }
            return
        }
        ic.performEditorAction(actionId)
    }

    private fun onCancelTapped() {
        if (state == State.RECORDING) {
            finishRecording(upload = false)
        } else {
            cancelWork()
        }
        hapticTap()
    }

    private fun onLevelChanged(level: Float) {
        // Keep the 500 ms UI callback tiny: cache one string and touch the
        // status view only when the blocked hint appears or disappears.
        val blocked = state == State.RECORDING && recorder?.isMicMaybeBlocked() == true
        waveView?.setAmplitude(level)
        if (blocked == blockedHintShown) return
        blockedHintShown = blocked
        if (blocked) {
            val hint = blockedHint ?: getString(R.string.ime_mic_blocked_hint).also { blockedHint = it }
            statusText?.text = hint
        }
    }

    private fun startRecording() {
        cancelWork()
        // Live-marker models stream over a continuous WebSocket session
        // (interim hypotheses replace the preview live); everything else
        // records-then-uploads the one-shot path below untouched.
        if (requiresLive(settings.model)) {
            startLiveStreaming()
            if (state == State.RECORDING) hapticTap()
            return
        }
        val rec = AudioRecorder(
            this@VoiceImeService,
            onLevel = { level ->
                mainHandler.post {
                    onLevelChanged(level)
                }
            },
        )
        recorder = rec
        try {
            rec.start()
        } catch (e: IllegalStateException) {
            recorder = null
            showError(getString(R.string.ime_error_mic))
            return
        }
        state = State.RECORDING
        recordStartMs = System.currentTimeMillis()
        lastTimerSecond = -1
        blockedHintShown = false
        renderState()
        statusText?.setText(R.string.ime_status_recording)
        startTimer()
        // 5 min hard cap in addition to the recorder's own auto-stop.
        // Named handle (not an anonymous lambda) so stopTimer() can remove
        // exactly this callback instead of wiping the whole message queue.
        capRunnable?.let { mainHandler.removeCallbacks(it) }
        val cap = Runnable { if (state == State.RECORDING) finishRecording(upload = true) }
        capRunnable = cap
        mainHandler.postDelayed(cap, 300_000)
    }

    /**
     * Live streaming path (cookbook pattern): socket opens on mic tap, mic
     * buffers stream while speaking, interim hypotheses REPLACE the status
     * preview live, Stop closes the turn and the final commits. The one-shot
     * record→upload path is untouched for non-live models.
     */
    private var liveStreamingSession: LlmClient.LiveSession? = null
    private val liveFinalParts = StringBuilder()
    private var liveLastInterim = ""
    /** Commit coroutine for the Stop flow; Cancel taps cancel this, not the socket. */
    private var liveCommitJob: kotlinx.coroutines.Job? = null
    /** Id of the session currently allowed to write live buffers. */
    private var liveSessionId = 0L
    private var liveSessionSeq = 0L

    private fun startLiveStreaming() {
        cancelWork()
        val keys = normalizeKeys(settings.apiKeys)
        if (keys.isEmpty()) {
            showError(getString(R.string.ime_error_no_key))
            return
        }
        val model = settings.model
        val session = llm.startLiveSession(
            key = keys[keyCursor % keys.size],
            baseUrl = settings.baseUrl,
            model = model,
            smartMode = settings.smartMode,
            onInterim = { preview ->
                val id = liveSessionSeq
                mainHandler.post {
                    if (state == State.RECORDING && id == liveSessionId) {
                        liveLastInterim = preview
                        statusText?.text = preview
                    }
                }
            },
            onFinal = { text ->
                val id = liveSessionSeq
                mainHandler.post {
                    if (id == liveSessionId &&
                        (state == State.RECORDING || state == State.UPLOADING)
                    ) {
                        liveFinalParts.append(text)
                        statusText?.text = liveFinalParts.toString()
                    }
                }
            },
            onError = { message ->
                mainHandler.post { showError(message) }
            },
        )
        liveStreamingSession = session
        liveSessionSeq += 1
        liveSessionId = liveSessionSeq
        val rec = AudioRecorder(
            this@VoiceImeService,
            onLevel = { level ->
                mainHandler.post {
                    onLevelChanged(level)
                }
            },
            onPcmChunk = { pcm ->
                session.sendPcm(pcm)
            },
        )
        recorder = rec
        try {
            rec.start()
        } catch (e: IllegalStateException) {
            recorder = null
            session.close()
            liveStreamingSession = null
            showError(getString(R.string.ime_error_mic))
            return
        }
        liveFinalParts.setLength(0)
        liveLastInterim = ""
        state = State.RECORDING
        recordStartMs = System.currentTimeMillis()
        lastTimerSecond = -1
        blockedHintShown = false
        renderState()
        statusText?.setText(R.string.ime_status_recording)
        startTimer()
        capRunnable?.let { mainHandler.removeCallbacks(it) }
        val cap = Runnable { if (state == State.RECORDING) finishRecording(upload = true) }
        capRunnable = cap
        mainHandler.postDelayed(cap, 300_000)
        if (state == State.RECORDING) hapticTap()
    }

    /**
     * Stop on a Live session: close the turn and commit whatever finalized
     * text arrived (plus any last interim preview as fallback). Never hangs:
     * no 75 s wait — the socket already streamed everything live.
     */
    private fun finishLiveStreaming(upload: Boolean) {
        stopTimer()
        val rec = recorder
        recorder = null
        val session = liveStreamingSession
        liveStreamingSession = null
        state = State.UPLOADING
        renderState()
        statusText?.setText(R.string.ime_status_uploading)
        liveCommitJob?.cancel()
        liveCommitJob = serviceScope.launch {
            val peak = try {
                withContext(Dispatchers.IO) { rec?.stop() }?.second ?: 0f
            } catch (e: Exception) {
                0f
            }
            if (!upload) {
                try {
                    session?.close()
                } catch (e: Exception) {
                    // Already gone.
                }
                lastWav = null
                state = State.IDLE
                renderState()
                return@launch
            }
            // Hand the session to the commit flow: lifecycle must not kill it.
            session?.detach()
            // Read buffers BEFORE the silence gate: quiet gain with a valid
            // server transcript still commits instead of crying no-speech.
            val preText = liveFinalParts.toString().trim()
                .ifEmpty { liveLastInterim.trim() }
            if (AudioRecorder.isSilent(peak) && preText.isEmpty()) {
                try {
                    session?.close()
                } catch (e: Exception) {
                    // Already gone.
                }
                lastWav = null
                showError(getString(R.string.ime_error_no_speech))
                return@launch
            }
            try {
                session?.finish()
            } catch (e: Exception) {
                // Session already dead; fall through to whatever arrived.
            }
            // Signal-driven: commit the moment the server finalizes
            // (turnComplete/first final/close-with-text); ~12 s fallback
            // commits from whatever interim/final text already arrived.
            val signaled = try {
                session?.awaitFinal(12_000) ?: false
            } catch (e: Exception) {
                false
            }
            if (!signaled) {
                android.util.Log.d("VoiceIme", "live-commit: no final signal in 12s, committing fallback")
            }
            try {
                session?.close()
            } catch (e: Exception) {
                // Socket already settled via turnComplete/onClosed.
            }
            val interim = liveLastInterim.trim()
            val text = liveFinalParts.toString().trim()
                .ifEmpty { interim }
                .ifEmpty {
                    val shown = statusText?.text?.toString().orEmpty().trim()
                    // The status view otherwise still holds "Transcribing…" —
                    // never commit a status string as a transcript.
                    if (shown == getString(R.string.ime_status_uploading) ||
                        shown == getString(R.string.ime_status_recording)
                    ) "" else shown
                }
            if (text.isEmpty()) {
                showError(getString(R.string.ime_error_empty))
            } else {
                val usedKeys = normalizeKeys(settings.apiKeys)
                if (usedKeys.isNotEmpty()) {
                    keyCursor = (keyCursor + 1) % usedKeys.size
                }
                deliverTranscript(text)
            }
        }
    }

    private fun finishRecording(upload: Boolean) {
        if (state != State.RECORDING) return
        // Live session owns its lifecycle; the one-shot path below is for
        // non-live models only.
        if (liveStreamingSession != null) {
            finishLiveStreaming(upload)
            return
        }
        stopTimer()
        val rec = recorder
        recorder = null
        // rec.stop() joins the recording thread (bounded 2 s): keep it off
        // the UI thread so Stop never janks, and show an intermediate state.
        state = State.UPLOADING
        renderState()
        statusText?.setText(R.string.ime_status_uploading)
        serviceScope.launch {
            val (wav, peak) = try {
                withContext(Dispatchers.IO) { rec?.stop() } ?: (ByteArray(0) to 0f)
            } catch (e: Exception) {
                ByteArray(0) to 0f
            }
            finishRecordingAfterStop(upload, wav, peak)
        }
    }

    private fun finishRecordingAfterStop(upload: Boolean, wav: ByteArray, peak: Float) {
        if (!upload) {
            lastWav = null
            state = State.IDLE
            renderState()
            return
        }
        if (AudioRecorder.isSilent(peak)) {
            lastWav = null
            showError(getString(R.string.ime_error_no_speech))
            return
        }
        if (wav.size <= 1000) {
            lastWav = null
            showError(getString(R.string.ime_error_no_audio))
            return
        }
        lastWav = wav
        transcribeJob?.cancel()
        // Already in UPLOADING (set before stop completed); just launch.
        val baseUrl = settings.baseUrl
        val apiKeys = settings.apiKeys
        val model = settings.model
        val customPrompt = settings.customPrompt
        val smartMode = settings.smartMode
        val start = keyCursor
        transcribeJob = serviceScope.launch {
            var transcript: String? = null
            var usedIndex: Int? = null
            var failure: String? = null
            try {
                val result = withContext(Dispatchers.IO) {
                    llm.transcribe(wav, apiKeys, baseUrl, model, start, customPrompt, smartMode)
                }
                transcript = result.first
                usedIndex = result.second
            } catch (e: TranscribeException) {
                failure = e.message ?: getString(R.string.ime_error_network)
            }
            if (transcript != null) {
                val used = usedIndex
                if (apiKeys.isNotEmpty() && used != null) {
                    keyCursor = (used + 1) % apiKeys.size
                }
                deliverTranscript(transcript)
            } else {
                showError(failure ?: getString(R.string.ime_error_network))
            }
        }
    }

    private fun retryLast() {
        val wav = lastWav ?: return
        transcribeJob?.cancel()
        // Exponential backoff on manual retries (immediate, 2s, 4s, cap 8s)
        // so hammering Retry during an outage doesn't burn keys instantly.
        val waitMs = retryBackoffMs
        retryBackoffMs = (if (waitMs == 0L) 2000L else waitMs * 2).coerceAtMost(8000L)
        state = State.UPLOADING
        renderState()
        statusText?.setText(R.string.ime_status_uploading)
        val baseUrl = settings.baseUrl
        val apiKeys = settings.apiKeys
        val model = settings.model
        val customPrompt = settings.customPrompt
        val smartMode = settings.smartMode
        val start = keyCursor
        transcribeJob = serviceScope.launch {
            if (waitMs > 0) kotlinx.coroutines.delay(waitMs)
            var transcript: String? = null
            var usedIndex: Int? = null
            var failure: String? = null
            try {
                val result = withContext(Dispatchers.IO) {
                    llm.transcribe(wav, apiKeys, baseUrl, model, start, customPrompt, smartMode)
                }
                transcript = result.first
                usedIndex = result.second
            } catch (e: TranscribeException) {
                failure = e.message ?: getString(R.string.ime_error_network)
            }
            if (transcript != null) {
                val used = usedIndex
                if (apiKeys.isNotEmpty() && used != null) {
                    keyCursor = (used + 1) % apiKeys.size
                }
                retryBackoffMs = 0L
                deliverTranscript(transcript)
            } else {
                showError(failure ?: getString(R.string.ime_error_network))
            }
        }
    }

    private fun deliverTranscript(text: String) {
        if (text.isBlank()) {
            showError(getString(R.string.ime_error_empty))
            return
        }
        lastWav = null
        maybeSaveClip(text)
        // Clipboard first so the text survives even if commit fails.
        try {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("voice-ime", text)
            if (passwordField) markClipSensitive(clip.description)
            cm.setPrimaryClip(clip)
            lastClipFingerprint = ClipboardPolicy.clipFingerprint("voice-ime", text, "text/plain")
        } catch (e: Exception) {
            // Clipboard unavailable; still try commitText.
        }
        val ic = currentInputConnection
        if (ic != null) {
            try {
                ic.finishComposingText()
                ic.beginBatchEdit()
                try {
                    ic.commitText(text, 1)
                } finally {
                    ic.endBatchEdit()
                }
            } catch (e: Exception) {
                // Fall through to clipboard-only path with toast.
            }
        }
        state = State.IDLE
        renderState()
        refreshClipStrip()
        statusText?.setText(R.string.ime_status_idle)
        hapticConfirm()
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
            Toast.makeText(this, R.string.ime_copied_paste, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showError(message: String) {
        state = State.ERROR
        renderState()
        statusText?.text = message
    }

    private fun openSettings() {
        val intent = Intent(this, SettingsActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    private fun cancelWork() {
        stopTimer()
        transcribeJob?.cancel()
        transcribeJob = null
        liveCommitJob?.cancel()
        liveCommitJob = null
        llm.cancel()
        // A detached session belongs to the commit flow: kill the job above,
        // never the socket — the job's cancellation settles it. An attached
        // (still recording) session dies here.
        val attached = liveStreamingSession
        liveStreamingSession = null
        if (attached != null && !attached.isDetached()) {
            try {
                attached.close()
            } catch (e: Exception) {
                // Session already gone.
            }
        }
        liveFinalParts.setLength(0)
        liveLastInterim = ""
        recorder?.discard()
        recorder = null
        lastWav = null
        if (state == State.RECORDING || state == State.UPLOADING) {
            state = State.IDLE
            mainHandler.post { renderState() }
        }
    }

    /**
     * Sets visibility only when it actually changes. renderState() runs on
     * every focus/timer-adjacent event; redundant setVisibility calls would
     * each schedule a full measure/layout pass on the IME window.
     */
    private fun setVisible(view: View?, visible: Boolean) {
        if (view != null && (view.visibility == View.VISIBLE) != visible) {
            view.visibility = if (visible) View.VISIBLE else View.GONE
        }
    }

    private fun renderState() {
        statusDot?.backgroundTintList = when (state) {
            State.RECORDING -> dotRecording
            State.UPLOADING -> dotUploading
            State.ERROR -> dotError
            else -> dotIdle
        }
        val recording = state == State.RECORDING
        val uploading = state == State.UPLOADING
        val error = state == State.ERROR
        micButton?.isEnabled = !uploading
        setVisible(stopButton, recording)
        setVisible(cancelButton, recording || uploading)
        setVisible(retryButton, error && lastWav != null)
        setVisible(settingsButton, error)
        val idleish = state == State.IDLE || error
        setVisible(enterButton, idleish)
        if (idleish) {
            val actionId = lastEditorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION)
                ?: EditorInfo.IME_ACTION_UNSPECIFIED
            if (actionId != lastEnterAction) {
                lastEnterAction = actionId
                enterButton?.text = enterLabel(actionId)
            }
        } else {
            lastEnterAction = Int.MIN_VALUE
        }
        // Collapse the actions row when the error buttons are hidden so the
        // keyboard stays compact instead of holding an empty strip. The retry
        // button only shows on error, so `error` covers the whole row.
        setVisible(actionsRow, error)
        if (state == State.IDLE) {
            statusText?.setText(R.string.ime_status_idle)
            timerText?.text = "0:00"
            waveView?.reset()
        }
    }

    private fun startTimer() {
        stopTimer()
        val runnable = object : Runnable {
            override fun run() {
                val seconds = ((System.currentTimeMillis() - recordStartMs) / 1000).toInt().coerceAtLeast(0)
                if (seconds != lastTimerSecond) {
                    lastTimerSecond = seconds
                    timerText?.text = formatElapsed(seconds)
                }
                if (state == State.RECORDING) {
                    mainHandler.postDelayed(this, 500)
                }
            }
        }
        timerRunnable = runnable
        mainHandler.post(runnable)
    }

    private fun stopTimer() {
        timerRunnable?.let { mainHandler.removeCallbacks(it) }
        timerRunnable = null
        capRunnable?.let { mainHandler.removeCallbacks(it) }
        capRunnable = null
    }

    // ---- Clipboard history (opt-in, text-only) ----

    private fun store(): ClipboardStore? {
        var s = clipboardStore
        if (s == null) {
            s = ClipboardStore.forContext(this)
            clipboardStore = s
        }
        return s
    }

    private fun applyClipPrefs() {
        val s = store() ?: return
        s.enabled = ClipboardHistoryActivity.isEnabled(this)
        s.maxUnpinned = ClipboardHistoryActivity.maxCount(this)
        s.ttlMs = ClipboardHistoryActivity.ttlMs(this)
    }

    private fun refreshClipStrip() {
        val strip = clipStrip ?: return
        val scroll = clipStripScroll ?: return
        if (!ClipboardHistoryActivity.isEnabled(this)) {
            clipEntries = emptyList()
            mainHandler.post {
                strip.removeAllViews()
                clipMoreButton?.let { strip.addView(it) }
                scroll.visibility = View.GONE
            }
            return
        }
        serviceScope.launch {
            val s = store()
            val entries = try {
                withContext(Dispatchers.IO) {
                    if (s == null) emptyList() else s.load()
                }
            } catch (e: Exception) {
                emptyList()
            }
            clipEntries = entries
            mainHandler.post { renderClipStrip(entries) }
        }
    }

    private fun renderClipStrip(entries: List<ClipboardEntry>) {
        val strip = clipStrip ?: return
        val scroll = clipStripScroll ?: return
        if (passwordField) {
            strip.removeAllViews()
            scroll.visibility = View.GONE
            return
        }
        strip.removeAllViews()
        val limit = clipStripLimit()
        var visible = entries.take(limit)
        if (visible.isEmpty()) {
            clipMoreButton?.let { strip.addView(it) }
            scroll.visibility = View.GONE
            return
        }
        for (entry in visible) {
            val button = Button(this).apply {
                text = ClipboardPolicy.sanitizePreview(entry)
                textSize = resources.getDimension(R.dimen.clip_strip_text_size) /
                    resources.displayMetrics.scaledDensity
                minHeight = 0
                minimumHeight = resources.getDimensionPixelSize(R.dimen.voice_strip_min_height)
                setOnClickListener { commitClipEntry(entry) }
                setOnLongClickListener {
                    serviceScope.launch {
                        val updated = try {
                            withContext(Dispatchers.IO) { store()?.deleteAt(entry.id) }
                        } catch (e: Exception) {
                            null
                        }
                        if (updated != null) {
                            clipEntries = updated
                            mainHandler.post { renderClipStrip(updated) }
                        }
                    }
                    true
                }
            }
            strip.addView(button)
        }
        val more = clipMoreButton
        if (more != null) {
            (more.parent as? ViewGroup)?.removeView(more)
            more.visibility = if (entries.size > limit) View.VISIBLE else View.GONE
            strip.addView(more)
        }
        scroll.visibility = View.VISIBLE
    }

    /**
     * Inline history count: resource base, capped low when the IME window is
     * narrow (split-screen / divider-drag / small DeX window).
     */
    internal fun clipStripLimit(): Int {
        val compact = ImeLayoutPolicy.isCompactMode(
            inMultiWindow = false,
            windowWidthDp = currentWindowWidthDp(),
        )
        return ImeLayoutPolicy.clipLimit(CLIP_STRIP_LIMIT, compact)
    }

    /** Current IME window width in dp; 0 when the view isn't attached yet. */
    internal fun currentWindowWidthDp(): Float {
        val view = rootView ?: return 0f
        val widthPx = view.width
        if (widthPx <= 0) return 0f
        val density = resources.displayMetrics.density
        if (density <= 0f) return 0f
        return widthPx / density
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // Rotation / divider-drag / fold: re-render against the new window
        // size (compact caps, clipped popup) and drop a stale popup.
        dismissClipPopup()
        renderClipStrip(clipEntries)
        renderState()
    }

    override fun onEvaluateFullscreenMode(): Boolean = ImeLayoutPolicy.fullscreenMode()

    private fun commitClipEntry(entry: ClipboardEntry) {
        if (entry.text.isBlank()) return
        try {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("voice-ime", entry.text)
            if (passwordField || entry.isSensitive) markClipSensitive(clip.description)
            cm.setPrimaryClip(clip)
            lastClipFingerprint = ClipboardPolicy.clipFingerprint("voice-ime", entry.text, "text/plain")
        } catch (e: Exception) {
            return
        }
        val ic = currentInputConnection ?: return
        try {
            ic.finishComposingText()
            ic.beginBatchEdit()
            try {
                ic.commitText(entry.text, 1)
            } finally {
                ic.endBatchEdit()
            }
        } catch (e: Exception) {
            // Clipboard already holds the text; nothing further to do.
        }
        hapticConfirm()
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
            Toast.makeText(this, R.string.ime_copied_paste, Toast.LENGTH_SHORT).show()
        }
    }

    /** Captures our own transcript into history (point (a)): before clipboard overwrite. */
    private fun maybeSaveClip(text: String) {
        if (!ClipboardHistoryActivity.isEnabled(this)) return
        if (passwordField) return
        if (!ClipboardPolicy.shouldCapture(text)) {
            if (text.length > ClipboardPolicy.MAX_ITEM_CHARS) {
                mainHandler.post {
                    Toast.makeText(this, R.string.clipboard_too_large, Toast.LENGTH_SHORT).show()
                }
            }
            return
        }
        serviceScope.launch {
            try {
                withContext(Dispatchers.IO) { store()?.add(text) }
            } catch (e: Exception) {
                // History is best-effort; delivery already succeeded.
            }
        }
    }

    private fun registerClipListener() {
        if (!ClipboardHistoryActivity.isEnabled(this)) return
        if (clipListener != null) return
        val cm = try {
            getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        } catch (e: Exception) {
            return
        }
        val listener = ClipboardManager.OnPrimaryClipChangedListener {
            mainHandler.post { onPrimaryClipChanged(cm) }
        }
        clipListener = listener
        try {
            cm.addPrimaryClipChangedListener(listener)
        } catch (e: Exception) {
            clipListener = null
        }
    }

    private fun unregisterClipListener() {
        val listener = clipListener ?: return
        clipListener = null
        try {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.removePrimaryClipChangedListener(listener)
        } catch (e: Exception) {
            // Listener already gone; nothing to do.
        }
    }

    /** Point (b): external copies while the IME is visible. Never coerces at bind time. */
    private fun onPrimaryClipChanged(cm: ClipboardManager) {
        if (!ClipboardHistoryActivity.isEnabled(this)) return
        if (passwordField) return
        val info = lastEditorInfo
        if (info != null && ClipboardPolicy.hasNoPersonalizedLearning(info.imeOptions)) return
        val hasClip = try {
            cm.hasPrimaryClip()
        } catch (e: Exception) {
            false
        }
        if (!hasClip) return
        val desc = try {
            cm.primaryClipDescription
        } catch (e: Exception) {
            null
        }
        if (desc == null || !desc.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN)) return
        if (hasSensitiveExtra(desc)) return
        val label = try {
            desc.label?.toString()
        } catch (e: Exception) {
            null
        }
        val text = try {
            val item = cm.primaryClip?.getItemAt(0) ?: return
            // Explicit user copy into the visible IME: coerce only here, never at bind.
            item.coerceToText(this)?.toString()
        } catch (e: Exception) {
            return
        }
        if (text.isNullOrBlank()) return
        val fingerprint = ClipboardPolicy.clipFingerprint(label, text, ClipDescription.MIMETYPE_TEXT_PLAIN)
        if (fingerprint == lastClipFingerprint) return
        lastClipFingerprint = fingerprint
        if (!ClipboardPolicy.shouldCapture(text)) return
        serviceScope.launch {
            val updated = try {
                withContext(Dispatchers.IO) { store()?.add(text) } ?: return@launch
            } catch (e: Exception) {
                return@launch
            }
            clipEntries = updated
            mainHandler.post { renderClipStrip(updated) }
        }
    }

    private fun hasSensitiveExtra(desc: ClipDescription): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 33) return false
        return try {
            val extras = desc.extras ?: return false
            extras.containsKey(ClipboardPolicy.EXTRA_IS_SENSITIVE)
        } catch (e: Exception) {
            false
        }
    }

    private fun markClipSensitive(desc: ClipDescription) {
        if (android.os.Build.VERSION.SDK_INT < 33) return
        try {
            val extras = desc.extras ?: PersistableBundle()
            extras.putBoolean(ClipboardPolicy.EXTRA_IS_SENSITIVE, true)
            desc.extras = extras
        } catch (e: Exception) {
            // Sensitive flag is best-effort on older platforms.
        }
    }

    private fun showClipPopup() {
        dismissClipPopup()
        if (passwordField) return
        val root = rootView ?: return
        val entries = clipEntries
        if (entries.isEmpty()) {
            Toast.makeText(this, R.string.clipboard_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val list = ListView(this).apply {
            adapter = object : BaseAdapter() {
                override fun getCount(): Int = entries.size
                override fun getItem(position: Int): ClipboardEntry = entries[position]
                override fun getItemId(position: Int): Long = position.toLong()
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val row = convertView as? TextView ?: TextView(this@VoiceImeService).apply {
                        minimumHeight = resources.getDimensionPixelSize(R.dimen.clip_popup_row_min_height)
                        textSize = resources.getDimension(R.dimen.clip_popup_text_size) /
                            resources.displayMetrics.scaledDensity
                    }
                    row.text = ClipboardPolicy.sanitizePreview(getItem(position))
                    return row
                }
            }
            setOnItemClickListener { _, _, position, _ ->
                dismissClipPopup()
                commitClipEntry(entries[position])
            }
            setOnItemLongClickListener { _, _, position, _ ->
                val removed = entries[position]
                serviceScope.launch {
                    val updated = try {
                        withContext(Dispatchers.IO) { store()?.deleteAt(removed.id) }
                    } catch (e: Exception) {
                        null
                    }
                    if (updated != null) {
                        clipEntries = updated
                        mainHandler.post {
                            dismissClipPopup()
                            renderClipStrip(updated)
                            if (updated.isNotEmpty()) showClipPopup()
                        }
                    }
                }
                true
            }
        }
        val baseHeightPx = resources.getDimensionPixelSize(R.dimen.clip_popup_height)
        val viewHeightPx = rootView?.height ?: 0
        val popup = PopupWindow(
            list,
            ViewGroup.LayoutParams.MATCH_PARENT,
            ImeLayoutPolicy.popupHeightPx(baseHeightPx, viewHeightPx),
            true,
        )
        clipPopup = popup
        try {
            popup.showAtLocation(root, Gravity.BOTTOM, 0, 0)
        } catch (e: Exception) {
            clipPopup = null
        }
    }

    private fun dismissClipPopup() {
        try {
            clipPopup?.dismiss()
        } catch (e: Exception) {
            // Already dismissed.
        }
        clipPopup = null
    }

    private fun isPasswordField(info: EditorInfo?): Boolean {        if (info == null) return false
        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        if (cls != InputType.TYPE_CLASS_TEXT && cls != InputType.TYPE_CLASS_NUMBER) return false
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        return variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
            variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
    }
}
