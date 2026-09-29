package com.voiceime

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * System-wide host for the floating dictation button.
 *
 * This service is deliberately a thin invocation and insertion surface:
 * focused-editor discovery, overlay lifecycle, and cursor insertion live here;
 * recording stays in [AudioRecorder] and every transcription request stays in
 * the existing [LlmClient] backend. No second HTTP client, API key store, or
 * model implementation is introduced for the floating mode.
 *
 * Android requires an enabled AccessibilityService for a button that can appear
 * over another app and write to that app's focused editor. The service only
 * requests focused-editor events and never logs node text or content
 * descriptions. Password fields are rejected before a target is retained.
 */
class FloatingMicAccessibilityService : AccessibilityService() {

    private enum class State { IDLE, RECORDING, TRANSCRIBING, ERROR }

    companion object {
        private const val TAG = "VoiceImeFloating"
        private const val PREFS_NAME = "voice_ime_floating"
        private const val PREF_X = "x"
        private const val PREF_Y = "y"
        // Keep a 48 dp touch target while drawing a smaller ~44 dp visual
        // control, so the overlay is compact without becoming hard to tap.
        private const val BUTTON_DP = 48
        private const val MARGIN_DP = 8
        private const val FEEDBACK_OFFSET_DP = 12
        private const val FEEDBACK_DURATION_MS = 2_000L
        private const val LIVE_FINAL_TIMEOUT_MS = 12_000L
        private const val RECORD_CAP_MS = 300_000L

        @Volatile
        private var connectedInstance: FloatingMicAccessibilityService? = null

        /** Called by [MicPermissionActivity] after its runtime permission dialog closes. */
        fun onMicPermissionResult(granted: Boolean) {
            connectedInstance?.onPermissionResult(granted)
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val discardScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val llm = LlmClient()
    private lateinit var settings: SettingsStore

    private var state = State.IDLE
    private var recorder: AudioRecorder? = null
    private var liveSession: LlmClient.LiveSession? = null
    /** Session handed to the Stop/commit coroutine; kept reachable for teardown. */
    private var handedOffLiveSession: LlmClient.LiveSession? = null
    private var workJob: Job? = null
    private var capRunnable: Runnable? = null
    private var pendingPermissionTap = false
    private var keyCursor = 0

    private val liveGuard = LiveSessionGuard()
    private val liveFinalParts = StringBuilder()
    private var liveLastInterim = ""
    private var liveSessionId = 0L

    private var targetNode: AccessibilityNodeInfo? = null

    private var button: FloatingMicView? = null
    private var feedback: TextView? = null
    private var buttonParams: WindowManager.LayoutParams? = null
    private var feedbackParams: WindowManager.LayoutParams? = null
    private var peekAnimator: android.animation.ValueAnimator? = null
    private var isPeeked = false
    private var prePeekX: Int? = null
    private var idlePeekRunnable: Runnable? = null
    private var feedbackHideRunnable: Runnable? = null

    private val dp: Float
        get() = resources.displayMetrics.density

    private val screenWidth: Int
        get() = resources.displayMetrics.widthPixels

    private val screenHeight: Int
        get() = resources.displayMetrics.heightPixels

    override fun onCreate() {
        super.onCreate()
        connectedInstance = this
        settings = SettingsStore(this)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        connectedInstance = this
        // Accessibility may have been enabled while an editor was already open.
        mainHandler.post { refreshFocusedEditor() }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
                updateTargetFromEvent(event.source)
            }

            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                // Window transitions deliver the focused editor a moment after
                // the window event on several OEMs. One posted refresh avoids
                // polling and still handles dialogs/split-screen changes.
                scheduleFocusRefresh()
            }
        }
    }

    override fun onInterrupt() {
        // The accessibility framework is taking the service offline. Do not
        // leave a live mic or a window behind while it does so.
        cancelCurrentWork()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (connectedInstance === this) connectedInstance = null
        teardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown()
        connectedInstance = null
        serviceScope.cancel()
        discardScope.cancel()
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun scheduleFocusRefresh() {
        mainHandler.removeCallbacks(refreshFocusRunnable)
        mainHandler.postDelayed(refreshFocusRunnable, 100L)
    }

    private val refreshFocusRunnable = Runnable { refreshFocusedEditor() }

    /** Refreshes from the framework's current input focus without scraping the window tree. */
    private fun refreshFocusedEditor() {
        val root = try {
            rootInActiveWindow
        } catch (_: Exception) {
            null
        } ?: run {
            clearTarget()
            return
        }

        var focused: AccessibilityNodeInfo? = null
        try {
            focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            val copy = focused?.let { AccessibilityNodeInfo.obtain(it) }
            applyCandidate(copy)
        } catch (_: Exception) {
            clearTarget()
        } finally {
            try {
                focused?.recycle()
                root.recycle()
            } catch (_: Exception) {
            }
        }
    }

    /** Takes ownership of [source] and recycles the event-owned node. */
    private fun updateTargetFromEvent(source: AccessibilityNodeInfo?) {
        if (source == null) {
            clearTarget()
            scheduleFocusRefresh()
            return
        }
        val copy = try {
            AccessibilityNodeInfo.obtain(source)
        } catch (_: Exception) {
            null
        } finally {
            try {
                source.recycle()
            } catch (_: Exception) {
            }
        }
        applyCandidate(copy)
    }

    /** Applies the minimum target contract used by the overlay and insertion path. */
    private fun applyCandidate(candidate: AccessibilityNodeInfo?) {
        if (candidate == null) {
            clearTarget()
            return
        }
        val packageName = candidate.packageName?.toString()
        val eligible = FloatingMicPolicy.shouldShowForEditor(
            isEditable = candidate.isEditable,
            isFocused = candidate.isFocused,
            isPassword = candidate.isPassword,
            packageName = packageName,
            ownPackageName = packageName(),
        )
        if (!eligible) {
            try {
                candidate.recycle()
            } catch (_: Exception) {
            }
            clearTarget()
            return
        }

        replaceTarget(candidate)
        showOverlayIfNeeded()
        if (isPeeked) restoreFromPeek()
        armIdlePeekTimer()
    }

    private fun packageName(): String = applicationContext.packageName

    private fun replaceTarget(next: AccessibilityNodeInfo) {
        try {
            targetNode?.recycle()
        } catch (_: Exception) {
        }
        targetNode = next
    }

    private fun clearTarget() {
        try {
            targetNode?.recycle()
        } catch (_: Exception) {
        }
        targetNode = null
        val busy = state == State.RECORDING || state == State.TRANSCRIBING
        if (!FloatingMicPolicy.shouldKeepVisibleWhileBusy(busy)) removeOverlay()
    }

    private fun showOverlayIfNeeded() {
        if (button != null || targetNode == null) return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val size = dp(BUTTON_DP)
        val margin = dp(MARGIN_DP)
        val params = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            val savedX = prefs.getInt(PREF_X, Int.MIN_VALUE)
            val savedY = prefs.getInt(PREF_Y, Int.MIN_VALUE)
            x = if (savedX == Int.MIN_VALUE) screenWidth - size - margin else
                FloatingMicPolicy.dockedX(savedX, screenWidth, size, margin)
            y = if (savedY == Int.MIN_VALUE) screenHeight / 2 - size / 2 else
                FloatingMicPolicy.clampY(savedY, screenHeight, size, margin)
        }
        val mic = FloatingMicView(this).apply {
            setState(state.toViewState())
            setOnTouchListener { view, motionEvent -> handleButtonTouch(view, motionEvent) }
        }
        val bubble = TextView(this).apply {
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 13f
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(0xEE1C1C1E.toInt())
            }
            alpha = 0f
            visibility = View.VISIBLE
            isClickable = false
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        val bubbleWindow = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = params.x
            y = params.y
        }

        try {
            wm.addView(mic, params)
            button = mic
            buttonParams = params
            wm.addView(bubble, bubbleWindow)
            feedback = bubble
            feedbackParams = bubbleWindow
        } catch (t: Throwable) {
            Log.w(TAG, "Floating overlay could not attach", t)
            safeRemove(wm, bubble)
            safeRemove(wm, mic)
            button = null
            feedback = null
            buttonParams = null
            feedbackParams = null
            return
        }
        armIdlePeekTimer()
    }

    private fun handleButtonTouch(view: View, event: MotionEvent): Boolean {
        val params = buttonParams ?: return true
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        // A listener-local gesture state cannot survive separate listener
        // invocations, so ACTION_DOWN initializes fields on the view tag.
        val gesture = view.getTag(R.id.floating_gesture_state) as? GestureState
            ?: GestureState().also { view.setTag(R.id.floating_gesture_state, it) }
        val longPress = Runnable {
            if (state == State.TRANSCRIBING) {
                gesture.longPressFired = true
                cancelCurrentWork()
            }
        }

        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelIdlePeekTimer()
                gesture.downX = event.rawX
                gesture.downY = event.rawY
                gesture.startX = params.x
                gesture.startY = params.y
                gesture.consumedPeekRestore = false
                gesture.longPressFired = false
                if (isPeeked) {
                    restoreFromPeek()
                    // Ramblr-style two-tap restore: restoring a peeking
                    // control must not unexpectedly start the microphone.
                    gesture.consumedPeekRestore = true
                } else if (state == State.TRANSCRIBING) {
                    mainHandler.postDelayed(longPress, FloatingMicPolicy.LONG_PRESS_MS)
                    gesture.longPressRunnable = longPress
                }
                true
            }

            MotionEvent.ACTION_MOVE -> {
                val moved = abs(event.rawX - gesture.downX) + abs(event.rawY - gesture.downY)
                if (moved >= dp(FloatingMicPolicy.TAP_THRESHOLD_DP)) {
                    mainHandler.removeCallbacks(gesture.longPressRunnable)
                }
                if (!gesture.consumedPeekRestore) {
                    params.x = gesture.startX + (event.rawX - gesture.downX).toInt()
                    params.y = gesture.startY + (event.rawY - gesture.downY).toInt()
                    try {
                        wm.updateViewLayout(view, params)
                        updateFeedbackPosition()
                    } catch (_: Exception) {
                    }
                }
                true
            }

            MotionEvent.ACTION_UP -> {
                mainHandler.removeCallbacks(gesture.longPressRunnable)
                armIdlePeekTimer()
                if (gesture.consumedPeekRestore) return true
                if (gesture.longPressFired) return true
                val moved = abs(event.rawX - gesture.downX) + abs(event.rawY - gesture.downY)
                if (moved < dp(FloatingMicPolicy.TAP_THRESHOLD_DP)) {
                    onButtonTapped()
                } else {
                    val size = dp(BUTTON_DP)
                    val margin = dp(MARGIN_DP)
                    params.x = FloatingMicPolicy.dockedX(params.x, screenWidth, size, margin)
                    params.y = FloatingMicPolicy.clampY(params.y, screenHeight, size, margin)
                    try {
                        wm.updateViewLayout(view, params)
                        updateFeedbackPosition()
                    } catch (_: Exception) {
                    }
                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                        .putInt(PREF_X, params.x)
                        .putInt(PREF_Y, params.y)
                        .apply()
                }
                true
            }

            MotionEvent.ACTION_CANCEL -> {
                mainHandler.removeCallbacks(gesture.longPressRunnable)
                armIdlePeekTimer()
                true
            }

            else -> false
        }
    }

    private class GestureState {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var consumedPeekRestore = false
        var longPressFired = false
        var longPressRunnable: Runnable = Runnable {}
    }

    private fun onButtonTapped() {
        when (state) {
            State.IDLE -> startDictation()
            State.RECORDING -> stopDictation()
            State.TRANSCRIBING -> cancelCurrentWork()
            State.ERROR -> {
                setState(State.IDLE)
                startDictation()
            }
        }
    }

    private fun startDictation() {
        if (targetNode == null) {
            showFeedback("Place the cursor in a text field")
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            pendingPermissionTap = true
            showFeedback("Allow microphone access, then return here")
            try {
                startActivity(Intent(this, MicPermissionActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            } catch (t: Throwable) {
                pendingPermissionTap = false
                showError("Microphone permission is required")
            }
            return
        }
        val keys = normalizeKeys(settings.apiKeys)
        if (keys.isEmpty()) {
            showError("Add an API key in Settings")
            return
        }

        cancelIdlePeekTimer()
        stopPulse()
        liveFinalParts.setLength(0)
        liveLastInterim = ""
        setState(State.RECORDING)
        val model = settings.model
        val sessionId = liveGuard.next()
        liveSessionId = sessionId
        val session = if (requiresLive(model)) {
            llm.startLiveSession(
                key = keys[keyCursor % keys.size],
                baseUrl = settings.baseUrl,
                model = model,
                smartMode = settings.smartMode,
                onInterim = { text ->
                    mainHandler.post {
                        if (state == State.RECORDING && liveGuard.isCurrent(sessionId)) {
                            liveLastInterim = text
                            showFeedback(text.take(140), durationMs = 1_200L)
                        }
                    }
                },
                onFinal = { text ->
                    mainHandler.post {
                        if ((state == State.RECORDING || state == State.TRANSCRIBING) &&
                            liveGuard.isCurrent(sessionId)
                        ) {
                            liveFinalParts.append(text)
                            showFeedback(liveFinalParts.toString().take(140), durationMs = 1_500L)
                        }
                    }
                },
                onError = { message ->
                    mainHandler.post {
                        if (liveGuard.isCurrent(sessionId)) {
                            cancelCurrentWork()
                            showError(message)
                        }
                    }
                },
            )
        } else {
            null
        }
        liveSession = session

        val rec = AudioRecorder(
            context = this,
            onLevel = {},
            onPcmChunk = { pcm -> session?.sendPcm(pcm) },
        )
        recorder = rec
        try {
            rec.start()
        } catch (_: Exception) {
            recorder = null
            session?.close()
            liveSession = null
            showError("Microphone unavailable")
            return
        }
        button?.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
        startPulse()
        capRunnable = Runnable {
            if (state == State.RECORDING) stopDictation()
        }.also { mainHandler.postDelayed(it, RECORD_CAP_MS) }
    }

    private fun stopDictation() {
        if (state != State.RECORDING) return
        cancelIdlePeekTimer()
        stopPulse()
        capRunnable?.let(mainHandler::removeCallbacks)
        capRunnable = null
        val rec = recorder
        recorder = null
        val session = liveSession
        liveSession = null
        session?.detach()
        handedOffLiveSession = session
        setState(State.TRANSCRIBING)
        workJob?.cancel()
        val sessionId = liveSessionId
        workJob = serviceScope.launch {
            val captured = try {
                withContext(Dispatchers.IO) { rec?.stop() ?: (ByteArray(0) to 0f) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                ByteArray(0) to 0f
            }

            if (session != null) {
                session.finish()
                session.awaitFinal(LIVE_FINAL_TIMEOUT_MS)
                session.close()
                handedOffLiveSession = null
                if (!liveGuard.isCurrent(sessionId)) return@launch
                val text = liveFinalParts.toString().trim().ifEmpty { liveLastInterim.trim() }
                if (text.isBlank() && AudioRecorder.isSilent(captured.second)) {
                    showError("No speech detected — try again")
                } else if (text.isBlank()) {
                    showError("Got empty transcript — try again")
                } else {
                    val liveKeys = normalizeKeys(settings.apiKeys)
                    if (liveKeys.isNotEmpty()) keyCursor = (keyCursor + 1) % liveKeys.size
                    deliverTranscript(text)
                }
                return@launch
            }

            if (AudioRecorder.isSilent(captured.second) || captured.first.size <= 1_000) {
                showError("No speech detected — try again")
                return@launch
            }
            val result = try {
                llm.transcribe(
                    wav = captured.first,
                    apiKeys = settings.apiKeys,
                    baseUrl = settings.baseUrl,
                    model = settings.model,
                    startIndex = keyCursor,
                    customPrompt = settings.customPrompt,
                    smartMode = settings.smartMode,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: TranscribeException) {
                showError(e.message ?: "Transcription failed")
                return@launch
            }
            val batchKeys = normalizeKeys(settings.apiKeys)
            if (batchKeys.isNotEmpty()) keyCursor = (result.second + 1) % batchKeys.size
            deliverTranscript(result.first)
        }
    }

    private fun deliverTranscript(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) {
            showError("Got empty transcript — try again")
            return
        }
        if (targetNode == null) {
            showError("Cursor unavailable — try again")
            return
        }
        val inserted = insertAtCursor(clean)
        if (inserted) {
            setState(State.IDLE)
            showFeedback("Inserted")
            button?.performHapticFeedback(
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    android.view.HapticFeedbackConstants.CONFIRM
                } else {
                    android.view.HapticFeedbackConstants.KEYBOARD_TAP
                },
            )
        } else {
            setState(State.ERROR)
            showFeedback("Copied — tap to paste", durationMs = 6_000L)
        }
        armIdlePeekTimer()
    }

    /**
     * Directly replaces the selected range with the transcript, preserving
     * text before and after the cursor. A clipboard/paste fallback covers
     * Compose and custom editors that do not expose ACTION_SET_TEXT.
     */
    private fun insertAtCursor(text: String): Boolean {
        val node = targetNode ?: return false
        try {
            node.refresh()
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val current = FloatingMicPolicy.textForInsertion(
                node.text?.toString(),
                node.hintText?.toString(),
            )
            if (current != null) {
                val rawStart = node.textSelectionStart
                val rawEnd = node.textSelectionEnd
                val start = if (rawStart < 0) current.length else rawStart.coerceIn(0, current.length)
                val end = if (rawEnd < 0) start else rawEnd.coerceIn(start, current.length)
                val replacement = current.substring(0, start) + text + current.substring(end)
                val args = Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        replacement,
                    )
                }
                if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                    val cursor = start + text.length
                    val selection = Bundle().apply {
                        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
                        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
                    }
                    node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selection)
                    return true
                }
            }
        } catch (_: Exception) {
            // Fall through to the platform paste action.
        }

        return pasteFromClipboard(text, node)
    }

    private fun pasteFromClipboard(text: String, node: AccessibilityNodeInfo): Boolean {
        return try {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Voice IME", text))
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        } catch (_: Exception) {
            false
        }
    }

    private fun showError(message: String) {
        stopPulse()
        setState(State.ERROR)
        showFeedback(message, durationMs = 4_000L)
    }

    private fun onPermissionResult(granted: Boolean) {
        if (!pendingPermissionTap) return
        pendingPermissionTap = false
        if (granted) {
            mainHandler.post { if (state == State.IDLE) startDictation() }
        } else {
            mainHandler.post { showError("Microphone permission is required") }
        }
    }

    private fun cancelCurrentWork() {
        pendingPermissionTap = false
        capRunnable?.let(mainHandler::removeCallbacks)
        capRunnable = null
        liveGuard.next()
        workJob?.cancel()
        workJob = null
        llm.cancel()
        val session = liveSession
        liveSession = null
        val handedOff = handedOffLiveSession
        handedOffLiveSession = null
        try {
            session?.close()
            handedOff?.close()
        } catch (_: Exception) {
        }
        val rec = recorder
        recorder = null
        if (rec != null) {
            discardScope.launch {
                try {
                    rec.discard()
                } catch (_: Exception) {
                }
            }
        }
        liveFinalParts.setLength(0)
        liveLastInterim = ""
        stopPulse()
        setState(State.IDLE)
        armIdlePeekTimer()
    }

    private fun setState(next: State) {
        state = next
        button?.setState(next.toViewState())
        if (next != State.IDLE) cancelIdlePeekTimer()
        if (next != State.RECORDING) stopPulse()
        if (next == State.IDLE) armIdlePeekTimer()
    }

    private fun State.toViewState(): FloatingMicView.State = when (this) {
        State.IDLE -> FloatingMicView.State.IDLE
        State.RECORDING -> FloatingMicView.State.RECORDING
        State.TRANSCRIBING -> FloatingMicView.State.TRANSCRIBING
        State.ERROR -> FloatingMicView.State.ERROR
    }

    private fun startPulse() {
        if (isReducedMotionEnabled()) return
        val view = button ?: return
        view.animate().cancel()
        view.alpha = 1f
        view.animate().alpha(0.4f).setDuration(500L).withEndAction {
            view.animate().alpha(1f).setDuration(500L).withEndAction {
                if (state == State.RECORDING) startPulse()
            }.start()
        }.start()
    }

    private fun stopPulse() {
        button?.animate()?.cancel()
        button?.alpha = 1f
    }

    private fun isReducedMotionEnabled(): Boolean = try {
        Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE) == 0f
    } catch (_: Settings.SettingNotFoundException) {
        false
    }

    private fun armIdlePeekTimer() {
        cancelIdlePeekTimer()
        if (state != State.IDLE || button == null || targetNode == null) return
        val peekRunnable = Runnable { attemptPeek() }
        idlePeekRunnable = peekRunnable
        mainHandler.postDelayed(peekRunnable, FloatingMicPolicy.PEEK_DELAY_MS)
    }

    private fun cancelIdlePeekTimer() {
        idlePeekRunnable?.let(mainHandler::removeCallbacks)
        idlePeekRunnable = null
    }

    private fun attemptPeek() {
        val params = buttonParams ?: return
        if (state != State.IDLE || isPeeked || button == null) return
        isPeeked = true
        prePeekX = params.x
        val visible = dp(FloatingMicPolicy.PEEK_VISIBLE_DP)
        val target = FloatingMicPolicy.peekedX(
            params.x,
            screenWidth,
            params.width,
            visible,
            insetPx = 0,
        )
        animateButtonX(target)
    }

    private fun restoreFromPeek() {
        if (!isPeeked) return
        val params = buttonParams ?: return
        val target = prePeekX ?: FloatingMicPolicy.dockedX(
            params.x,
            screenWidth,
            params.width,
            dp(MARGIN_DP),
        )
        isPeeked = false
        prePeekX = null
        animateButtonX(target)
        armIdlePeekTimer()
    }

    private fun animateButtonX(targetX: Int) {
        val params = buttonParams ?: return
        val view = button ?: return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        peekAnimator?.cancel()
        if (isReducedMotionEnabled()) {
            params.x = targetX
            try {
                wm.updateViewLayout(view, params)
                updateFeedbackPosition()
            } catch (_: Exception) {
            }
            return
        }
        peekAnimator = android.animation.ValueAnimator.ofInt(params.x, targetX).apply {
            duration = FloatingMicPolicy.PEEK_ANIMATION_MS
            addUpdateListener { animation ->
                params.x = animation.animatedValue as Int
                try {
                    wm.updateViewLayout(view, params)
                    updateFeedbackPosition()
                } catch (_: Exception) {
                    cancel()
                }
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    peekAnimator = null
                }

                override fun onAnimationCancel(animation: android.animation.Animator) {
                    peekAnimator = null
                }
            })
        }.also { it.start() }
    }

    private fun showFeedback(text: String, durationMs: Long = FEEDBACK_DURATION_MS) {
        mainHandler.post {
            val view = feedback ?: return@post
            view.text = text
            view.visibility = View.VISIBLE
            view.animate().cancel()
            view.alpha = 0f
            updateFeedbackPosition()
            view.animate().alpha(1f).setDuration(120L).start()
            view.post { if (feedback === view) updateFeedbackPosition() }
            feedbackHideRunnable?.let(mainHandler::removeCallbacks)
            val hideRunnable = Runnable {
                view.animate().alpha(0f).setDuration(180L).start()
            }
            feedbackHideRunnable = hideRunnable
            mainHandler.postDelayed(hideRunnable, durationMs)
        }
    }

    private fun updateFeedbackPosition() {
        val bubble = feedback ?: return
        val bubbleParams = feedbackParams ?: return
        val params = buttonParams ?: return
        val margin = dp(MARGIN_DP)
        val gap = dp(FEEDBACK_OFFSET_DP)
        val measuredHeight = bubble.height.coerceAtLeast(dp(40))
        val maxX = (screenWidth - bubble.width - margin).coerceAtLeast(margin)
        bubbleParams.x = (params.x - gap).coerceIn(margin, maxX)
        val opensUp = params.y + params.height / 2 > screenHeight / 2
        bubbleParams.y = if (opensUp) {
            (params.y - measuredHeight - margin).coerceAtLeast(margin)
        } else {
            (params.y + params.height + margin).coerceAtMost(screenHeight - measuredHeight - margin)
        }
        try {
            (getSystemService(WINDOW_SERVICE) as WindowManager).updateViewLayout(bubble, bubbleParams)
        } catch (_: Exception) {
        }
    }

    private fun removeOverlay() {
        cancelIdlePeekTimer()
        feedbackHideRunnable?.let(mainHandler::removeCallbacks)
        feedbackHideRunnable = null
        peekAnimator?.cancel()
        peekAnimator = null
        isPeeked = false
        prePeekX = null
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        feedback?.let { safeRemove(wm, it) }
        button?.let { safeRemove(wm, it) }
        feedback = null
        button = null
        feedbackParams = null
        buttonParams = null
    }

    private fun safeRemove(wm: WindowManager, view: View) {
        try {
            wm.removeView(view)
        } catch (_: IllegalArgumentException) {
            // The accessibility window token may already be gone.
        } catch (_: Exception) {
        }
    }

    private fun teardown() {
        cancelCurrentWork()
        try {
            targetNode?.recycle()
        } catch (_: Exception) {
        }
        targetNode = null
        removeOverlay()
    }

    private fun dp(value: Int): Int = (value * dp).toInt()
}
