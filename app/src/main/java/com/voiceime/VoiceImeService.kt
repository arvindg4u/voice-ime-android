package com.voiceime

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.ImageButton
import android.widget.ProgressBar
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
    }

    private var rootView: View? = null
    private var micButton: ImageButton? = null
    private var statusText: TextView? = null
    private var timerText: TextView? = null
    private var levelBar: ProgressBar? = null
    private var stopButton: Button? = null
    private var cancelButton: Button? = null
    private var retryButton: Button? = null
    private var settingsButton: Button? = null
    private var enterButton: Button? = null
    private var lastEditorInfo: EditorInfo? = null

    private var state: State = State.IDLE
    private var recorder: AudioRecorder? = null
    private var transcribeJob: Job? = null
    private var timerRunnable: Runnable? = null
    private var recordStartMs: Long = 0L
    private var lastWav: ByteArray? = null
    private var passwordArmed = false
    private var passwordField = false
    private var keyCursor: Int = 0

    private lateinit var settings: SettingsStore
    private val llm = LlmClient()

    override fun onCreate() {
        super.onCreate()
        settings = SettingsStore(this)
    }

    override fun onCreateInputView(): View {
        rootView?.let { return it }
        val view = layoutInflater.inflate(R.layout.ime_voice_view, null)
        micButton = view.findViewById(R.id.imeMic)
        statusText = view.findViewById(R.id.imeStatus)
        timerText = view.findViewById(R.id.imeTimer)
        levelBar = view.findViewById(R.id.imeLevel)
        stopButton = view.findViewById(R.id.imeStop)
        cancelButton = view.findViewById(R.id.imeCancel)
        retryButton = view.findViewById(R.id.imeRetry)
        settingsButton = view.findViewById(R.id.imeSettings)
        enterButton = view.findViewById(R.id.imeEnter)

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

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        lastEditorInfo = info
        passwordField = isPasswordField(info)
        passwordArmed = false
        renderState()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        lastEditorInfo = null
        cancelWork()
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        cancelWork()
    }

    override fun onDestroy() {
        cancelWork()
        serviceScope.cancel()
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
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
            return
        }
        if (settings.apiKeys.isEmpty()) {
            showError(getString(R.string.ime_error_no_key))
            return
        }
        startRecording()
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
    }

    private fun startRecording() {
        cancelWork()
        val rec = AudioRecorder(
            this@VoiceImeService,
            onLevel = { level ->
                mainHandler.post {
                    levelBar?.progress = (level * 100).toInt().coerceIn(0, 100)
                    if (state == State.RECORDING && recorder?.isMicMaybeBlocked() == true) {
                        val hint = getString(R.string.ime_mic_blocked_hint)
                        if (statusText?.text.toString() != hint) {
                            statusText?.text = hint
                        }
                    }
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
        renderState()
        statusText?.setText(R.string.ime_status_recording)
        startTimer()
        // 5 min hard cap in addition to the recorder's own auto-stop.
        mainHandler.postDelayed({ if (state == State.RECORDING) finishRecording(upload = true) }, 300_000)
    }

    private fun finishRecording(upload: Boolean) {
        if (state != State.RECORDING) return
        stopTimer()
        val rec = recorder
        recorder = null
        val (wav, peak) = try {
            rec?.stop() ?: (ByteArray(0) to 0f)
        } catch (e: Exception) {
            ByteArray(0) to 0f
        }
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
        state = State.UPLOADING
        renderState()
        statusText?.setText(R.string.ime_status_uploading)
        val baseUrl = settings.baseUrl
        val apiKeys = settings.apiKeys
        val model = settings.model
        val customPrompt = settings.customPrompt
        val start = keyCursor
        transcribeJob = serviceScope.launch {
            var transcript: String? = null
            var usedIndex: Int? = null
            var failure: String? = null
            try {
                val result = withContext(Dispatchers.IO) {
                    llm.transcribe(wav, apiKeys, baseUrl, model, start, customPrompt)
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
        state = State.UPLOADING
        renderState()
        statusText?.setText(R.string.ime_status_uploading)
        val baseUrl = settings.baseUrl
        val apiKeys = settings.apiKeys
        val model = settings.model
        val customPrompt = settings.customPrompt
        val start = keyCursor
        transcribeJob = serviceScope.launch {
            var transcript: String? = null
            var usedIndex: Int? = null
            var failure: String? = null
            try {
                val result = withContext(Dispatchers.IO) {
                    llm.transcribe(wav, apiKeys, baseUrl, model, start, customPrompt)
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

    private fun deliverTranscript(text: String) {
        if (text.isBlank()) {
            showError(getString(R.string.ime_error_empty))
            return
        }
        lastWav = null
        // Clipboard first so the text survives even if commit fails.
        try {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("voice-ime", text))
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
        statusText?.setText(R.string.ime_status_idle)
        Toast.makeText(this, R.string.ime_copied_paste, Toast.LENGTH_SHORT).show()
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
        llm.cancel()
        recorder?.discard()
        recorder = null
        lastWav = null
        if (state == State.RECORDING || state == State.UPLOADING) {
            state = State.IDLE
            mainHandler.post { renderState() }
        }
    }

    private fun renderState() {
        val recording = state == State.RECORDING
        val uploading = state == State.UPLOADING
        val error = state == State.ERROR
        micButton?.isEnabled = !uploading
        stopButton?.visibility = if (recording) View.VISIBLE else View.GONE
        cancelButton?.visibility = if (recording || uploading) View.VISIBLE else View.GONE
        retryButton?.visibility = if (error && lastWav != null) View.VISIBLE else View.GONE
        settingsButton?.visibility = if (error) View.VISIBLE else View.GONE
        val idleish = state == State.IDLE || error
        enterButton?.visibility = if (idleish) View.VISIBLE else View.GONE
        if (idleish) {
            val actionId = lastEditorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION)
                ?: EditorInfo.IME_ACTION_UNSPECIFIED
            enterButton?.text = enterLabel(actionId)
        }
        if (state == State.IDLE) {
            statusText?.setText(R.string.ime_status_idle)
            timerText?.text = "0:00"
            levelBar?.progress = 0
        }
    }

    private fun startTimer() {
        stopTimer()
        val runnable = object : Runnable {
            override fun run() {
                val elapsed = System.currentTimeMillis() - recordStartMs
                val seconds = (elapsed / 1000).toInt().coerceAtLeast(0)
                timerText?.text = "%d:%02d".format(seconds / 60, seconds % 60)
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
        mainHandler.removeCallbacksAndMessages(null)
    }

    private fun isPasswordField(info: EditorInfo?): Boolean {
        if (info == null) return false
        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        if (cls != InputType.TYPE_CLASS_TEXT && cls != InputType.TYPE_CLASS_NUMBER) return false
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        return variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
            variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
    }
}
