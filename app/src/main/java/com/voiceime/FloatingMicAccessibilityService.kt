package com.voiceime

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentCallbacks
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import androidx.annotation.RequiresApi
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
 * requests focused-editor events (plus Android 13+ InputMethod start/finish
 * callbacks) and never logs node text or content descriptions. Password fields
 * are rejected before a target is retained.
 */
class FloatingMicAccessibilityService : AccessibilityService() {

    private enum class State { IDLE, RECORDING, TRANSCRIBING, ERROR }

    companion object {
        private const val TAG = "VoiceImeFloating"
        private const val PREFS_NAME = "voice_ime_floating"
        private const val PREF_X = "x"
        private const val PREF_Y = "y"
        private const val PREF_DOCK_RIGHT = "dock_right"
        private const val PREF_Y_FRACTION = "y_fraction"
        // Keep a 48 dp touch target while drawing a smaller ~44 dp visual
        // control, so the overlay is compact without becoming hard to tap.
        private const val BUTTON_DP = 48
        private const val MARGIN_DP = 8
        private const val FEEDBACK_OFFSET_DP = 12
        private const val FEEDBACK_DURATION_MS = 2_000L
        private const val LIVE_PREVIEW_MAX_CHARS = 140
        private const val LIVE_FINAL_TIMEOUT_MS = 12_000L
        private const val RECORD_CAP_MS = AudioRecorder.MAX_DURATION_MS

        @Volatile
        private var connectedInstance: FloatingMicAccessibilityService? = null

        /**
         * True when Android's AccessibilityManagerService has bound this
         * service in-process. Used by [SettingsActivity] to detect Samsung One
         * UI Deep Sleep / stalled accessibility bindings.
         */
        fun isServiceConnected(): Boolean = connectedInstance != null

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
    private var clipboardStore: ClipboardStore? = null

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
    private var hasActiveImeTarget = false
    private var imeSessionSnapshot = FloatingEditorResolver.ImeSessionSnapshot()
    private var discoveryStepIndex = 0

    private var overlayContext: Context? = null
    private var overlayDisplayId: Int = Display.DEFAULT_DISPLAY
    private var overlayWindowManager: WindowManager? = null
    private var safeArea = FloatingEditorResolver.DisplaySafeArea(widthPx = 1080, heightPx = 1920)
    private var dockRight = true
    private var yFraction = 0.5f

    private var button: FloatingMicView? = null
    private var feedback: TextView? = null
    private var buttonParams: WindowManager.LayoutParams? = null
    private var feedbackParams: WindowManager.LayoutParams? = null
    private var peekAnimator: android.animation.ValueAnimator? = null
    private var isPeeked = false
    private var prePeekX: Int? = null
    private var idlePeekRunnable: Runnable? = null
    private var peekRestoreRunnable: Runnable? = null
    private var feedbackHideRunnable: Runnable? = null

    private val density: Float
        get() = (overlayContext ?: this).resources.displayMetrics.density

    private val discoveryStepRunnable = object : Runnable {
        override fun run() {
            val offsets = FloatingMicPolicy.FOCUS_DISCOVERY_OFFSETS_MS
            if (discoveryStepIndex >= offsets.size) return
            val step = discoveryStepIndex
            discoveryStepIndex++
            refreshFocusedEditor(eventSource = null, eventConfirmsSelection = false)
            val settled = button != null && targetNode != null && offsets[step] >= 220L
            if (!settled && discoveryStepIndex < offsets.size) {
                val nextDelay = (offsets[discoveryStepIndex] - offsets[step]).coerceAtLeast(40L)
                mainHandler.postDelayed(this, nextDelay)
            }
        }
    }

    private val overlayComponentCallbacks = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) {
            mainHandler.post { onDisplayGeometryChanged() }
        }

        @Deprecated("Deprecated in Java")
        override fun onLowMemory() = Unit
    }

    override fun onCreate() {
        super.onCreate()
        settings = SettingsStore(this)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        connectedInstance = this
        if (!::settings.isInitialized) {
            settings = SettingsStore(this)
        }
        FloatingEditorResolver.reinforceServiceFlags(this)
        bindOverlayDisplay(Display.DEFAULT_DISPLAY)
        loadSavedDockPreferences()
        // Accessibility may have been enabled while an editor was already open.
        mainHandler.post {
            refreshFocusedEditor(eventSource = null, eventConfirmsSelection = false)
            startDiscoveryWave()
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onCreateInputMethod(): android.accessibilityservice.InputMethod =
        FloatingEditorResolver.Api33Helper.createInputMethod(
            service = this,
            listener = object : FloatingEditorResolver.Api33Helper.Listener {
                override fun onImeSessionChanged(snapshot: FloatingEditorResolver.ImeSessionSnapshot) {
                    mainHandler.post {
                        imeSessionSnapshot = snapshot
                        refreshFocusedEditor(eventSource = null, eventConfirmsSelection = false)
                        if (snapshot.inputStarted) {
                            startDiscoveryWave()
                        }
                    }
                }

                override fun onImeSelectionChanged() {
                    mainHandler.post {
                        refreshFocusedEditor(eventSource = null, eventConfirmsSelection = false)
                    }
                }
            },
        )

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        mainHandler.post { onDisplayGeometryChanged() }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val type = event.eventType
        when (type) {
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                val inspectSource = type == AccessibilityEvent.TYPE_VIEW_CLICKED ||
                    type == AccessibilityEvent.TYPE_VIEW_LONG_CLICKED ||
                    type == AccessibilityEvent.TYPE_VIEW_FOCUSED ||
                    type == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED
                val source = if (inspectSource) {
                    try {
                        event.source
                    } catch (_: Throwable) {
                        null
                    }
                } else {
                    null
                }
                try {
                    refreshFocusedEditor(
                        eventSource = source,
                        eventConfirmsSelection = type == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED,
                    )
                } finally {
                    FloatingEditorResolver.recycleQuietly(source)
                }

                if (type != AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED) {
                    startDiscoveryWave()
                }
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
        if (connectedInstance === this) connectedInstance = null
        serviceScope.cancel()
        discardScope.cancel()
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun startDiscoveryWave() {
        val offsets = FloatingMicPolicy.FOCUS_DISCOVERY_OFFSETS_MS
        if (offsets.isEmpty()) return
        mainHandler.removeCallbacks(discoveryStepRunnable)
        discoveryStepIndex = 0
        mainHandler.postDelayed(discoveryStepRunnable, offsets[0])
    }

    private fun currentImeSnapshot(): FloatingEditorResolver.ImeSessionSnapshot {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            imeSessionSnapshot = FloatingEditorResolver.Api33Helper.currentSnapshot(
                service = this,
                cached = imeSessionSnapshot,
            )
        }
        return imeSessionSnapshot
    }

    /**
     * Resolves active editor focus across event source, global FOCUS_INPUT,
     * interactive windows on all displays (split-screen, freeform, App Bubbles,
     * Samsung DeX), and Android 13+ InputMethod sessions.
     */
    private fun refreshFocusedEditor(
        eventSource: AccessibilityNodeInfo?,
        eventConfirmsSelection: Boolean,
    ) {
        if (!::settings.isInitialized) return
        safeArea = FloatingEditorResolver.readDisplaySafeArea(
            windowManager = activeWindowManager(),
            fallbackContext = overlayContext ?: this,
        )
        val resolution = FloatingEditorResolver.resolve(
            service = this,
            eventSource = eventSource,
            eventConfirmsSelection = eventConfirmsSelection,
            imeSnapshot = currentImeSnapshot(),
            ownPackageName = packageName(),
            fallbackSafeArea = safeArea,
        )
        if (resolution.shouldShow) {
            replaceTarget(resolution.targetNode)
            hasActiveImeTarget = resolution.backedByImeSession
            ensureOverlayOnDisplay(resolution.displayId)
            showOverlayIfNeeded()
            if (isPeeked && isBusy()) restoreFromPeek()
            armIdlePeekTimer()
            return
        }

        // Keep the current editor through transient Samsung IME/window hand-offs
        // if its retained node still refreshes as focused & eligible.
        if (targetNodeIsStillFocused()) {
            return
        }

        clearTarget()
    }

    private fun refreshEditorTargetBeforeAction(): Boolean {
        val resolution = FloatingEditorResolver.resolve(
            service = this,
            eventSource = null,
            eventConfirmsSelection = false,
            imeSnapshot = currentImeSnapshot(),
            ownPackageName = packageName(),
            fallbackSafeArea = safeArea,
        )
        if (resolution.shouldShow) {
            replaceTarget(resolution.targetNode)
            hasActiveImeTarget = resolution.backedByImeSession
            return targetNode != null || hasActiveImeTarget
        }
        if (targetNodeIsStillFocused()) {
            return true
        }
        replaceTarget(null)
        hasActiveImeTarget = false
        return false
    }

    private fun targetNodeIsStillFocused(): Boolean {
        val current = targetNode?.let { node ->
            try {
                @Suppress("DEPRECATION")
                AccessibilityNodeInfo.obtain(node)
            } catch (_: Exception) {
                null
            }
        } ?: return false
        return try {
            current.refresh() &&
                FloatingEditorResolver.isNodeEligible(
                    node = current,
                    ownPackageName = packageName(),
                    inputFocusConfirmed = false,
                )
        } catch (_: Exception) {
            false
        } finally {
            FloatingEditorResolver.recycleQuietly(current)
        }
    }

    private fun packageName(): String = applicationContext.packageName

    private fun hasAnyActiveTarget(): Boolean = targetNode != null || hasActiveImeTarget

    private fun isBusy(): Boolean = state == State.RECORDING || state == State.TRANSCRIBING

    private fun replaceTarget(next: AccessibilityNodeInfo?) {
        val old = targetNode
        targetNode = next
        if (old != null && old !== next) {
            FloatingEditorResolver.recycleQuietly(old)
        }
    }

    private fun clearTarget() {
        // A focus change while recording must not leave a stale live
        // hypothesis bubble attached to the old editor.
        if (isBusy()) hideFeedback()
        replaceTarget(null)
        hasActiveImeTarget = false
        if (!FloatingMicPolicy.shouldKeepVisibleWhileBusy(isBusy())) {
            removeOverlay()
        }
    }

    private fun activeWindowManager(): WindowManager =
        overlayWindowManager ?: (getSystemService(WINDOW_SERVICE) as WindowManager)

    private fun bindOverlayDisplay(displayId: Int) {
        unbindOverlayDisplayCallbacks()
        val ctx = FloatingEditorResolver.createOverlayContext(this, displayId)
        overlayContext = ctx
        overlayDisplayId = displayId
        overlayWindowManager = ctx.getSystemService(WINDOW_SERVICE) as? WindowManager
            ?: (getSystemService(WINDOW_SERVICE) as? WindowManager)
        if (ctx !== this) {
            try {
                ctx.registerComponentCallbacks(overlayComponentCallbacks)
            } catch (_: Throwable) {
                // Non-fatal if OEM context rejects callback registration.
            }
        }
        safeArea = FloatingEditorResolver.readDisplaySafeArea(overlayWindowManager, ctx)
    }

    private fun unbindOverlayDisplayCallbacks() {
        val ctx = overlayContext
        if (ctx != null && ctx !== this) {
            try {
                ctx.unregisterComponentCallbacks(overlayComponentCallbacks)
            } catch (_: Throwable) {
                // Ignore if already unregistered.
            }
        }
    }

    private fun ensureOverlayOnDisplay(targetDisplayId: Int) {
        if (overlayContext == null) {
            bindOverlayDisplay(targetDisplayId)
            return
        }
        if (overlayDisplayId == targetDisplayId || isBusy()) return
        removeOverlay()
        bindOverlayDisplay(targetDisplayId)
    }

    private fun loadSavedDockPreferences() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val size = dp(BUTTON_DP)
        val margin = dp(MARGIN_DP)
        if (prefs.contains(PREF_DOCK_RIGHT) && prefs.contains(PREF_Y_FRACTION)) {
            dockRight = prefs.getBoolean(PREF_DOCK_RIGHT, true)
            yFraction = prefs.getFloat(PREF_Y_FRACTION, 0.5f).coerceIn(0f, 1f)
            return
        }
        val savedX = prefs.getInt(PREF_X, Int.MIN_VALUE)
        val savedY = prefs.getInt(PREF_Y, Int.MIN_VALUE)
        if (savedX != Int.MIN_VALUE || savedY != Int.MIN_VALUE) {
            val resolvedX = if (savedX == Int.MIN_VALUE) {
                safeArea.widthPx - size - margin
            } else {
                savedX
            }
            val resolvedY = if (savedY == Int.MIN_VALUE) {
                safeArea.heightPx / 2 - size / 2
            } else {
                savedY
            }
            dockRight = FloatingMicPolicy.isRightDocked(resolvedX, safeArea.widthPx, size)
            yFraction = FloatingMicPolicy.yToFraction(
                y = resolvedY,
                screenHeight = safeArea.heightPx,
                windowSize = size,
                marginPx = margin,
                insetTopPx = safeArea.insetTopPx,
                insetBottomPx = safeArea.insetBottomPx,
            )
            saveDockPreferences(
                x = FloatingMicPolicy.dockedXInSafeArea(
                    dockRight = dockRight,
                    screenWidth = safeArea.widthPx,
                    windowSize = size,
                    marginPx = margin,
                    insetLeftPx = safeArea.insetLeftPx,
                    insetRightPx = safeArea.insetRightPx,
                ),
                y = FloatingMicPolicy.yFromFraction(
                    fraction = yFraction,
                    screenHeight = safeArea.heightPx,
                    windowSize = size,
                    marginPx = margin,
                    insetTopPx = safeArea.insetTopPx,
                    insetBottomPx = safeArea.insetBottomPx,
                ),
            )
        } else {
            dockRight = true
            yFraction = 0.5f
        }
    }

    private fun saveDockPreferences(x: Int, y: Int) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putInt(PREF_X, x)
            .putInt(PREF_Y, y)
            .putBoolean(PREF_DOCK_RIGHT, dockRight)
            .putFloat(PREF_Y_FRACTION, yFraction)
            .apply()
    }

    private fun onDisplayGeometryChanged() {
        val wm = activeWindowManager()
        safeArea = FloatingEditorResolver.readDisplaySafeArea(wm, overlayContext ?: this)
        val params = buttonParams ?: return
        val view = button ?: return
        val size = dp(BUTTON_DP)
        val margin = dp(MARGIN_DP)
        peekAnimator?.cancel()
        peekAnimator = null
        isPeeked = false
        prePeekX = null
        params.width = size
        params.height = size
        params.x = FloatingMicPolicy.dockedXInSafeArea(
            dockRight = dockRight,
            screenWidth = safeArea.widthPx,
            windowSize = size,
            marginPx = margin,
            insetLeftPx = safeArea.insetLeftPx,
            insetRightPx = safeArea.insetRightPx,
        )
        params.y = FloatingMicPolicy.yFromFraction(
            fraction = yFraction,
            screenHeight = safeArea.heightPx,
            windowSize = size,
            marginPx = margin,
            insetTopPx = safeArea.insetTopPx,
            insetBottomPx = safeArea.insetBottomPx,
        )
        try {
            wm.updateViewLayout(view, params)
            updateFeedbackPosition()
        } catch (_: Exception) {
        }
        armIdlePeekTimer()
    }

    private fun showOverlayIfNeeded() {
        if (button != null || !hasAnyActiveTarget()) return
        val wm = activeWindowManager()
        val viewContext = overlayContext ?: this
        safeArea = FloatingEditorResolver.readDisplaySafeArea(wm, viewContext)
        val size = dp(BUTTON_DP)
        val margin = dp(MARGIN_DP)
        val params = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = FloatingMicPolicy.dockedXInSafeArea(
                dockRight = dockRight,
                screenWidth = safeArea.widthPx,
                windowSize = size,
                marginPx = margin,
                insetLeftPx = safeArea.insetLeftPx,
                insetRightPx = safeArea.insetRightPx,
            )
            y = FloatingMicPolicy.yFromFraction(
                fraction = yFraction,
                screenHeight = safeArea.heightPx,
                windowSize = size,
                marginPx = margin,
                insetTopPx = safeArea.insetTopPx,
                insetBottomPx = safeArea.insetBottomPx,
            )
            title = "VoiceImeFloatingMic"
        }
        val mic = FloatingMicView(viewContext).apply {
            setState(state.toViewState())
            keepScreenOn = isBusy()
            setOnTouchListener { view, motionEvent -> handleButtonTouch(view, motionEvent) }
        }
        val bubble = TextView(viewContext).apply {
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
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = params.x
            y = params.y
            title = "VoiceImeFloatingFeedback"
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
        val wm = activeWindowManager()
        val gesture = view.getTag(R.id.floating_gesture_state) as? GestureState
            ?: GestureState().also { view.setTag(R.id.floating_gesture_state, it) }
        val longPress = Runnable {
            if (state == State.TRANSCRIBING || state == State.RECORDING) {
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
                } else if (isBusy()) {
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
                    dockRight = FloatingMicPolicy.isRightDocked(params.x, safeArea.widthPx, size)
                    params.x = FloatingMicPolicy.dockedXInSafeArea(
                        dockRight = dockRight,
                        screenWidth = safeArea.widthPx,
                        windowSize = size,
                        marginPx = margin,
                        insetLeftPx = safeArea.insetLeftPx,
                        insetRightPx = safeArea.insetRightPx,
                    )
                    params.y = FloatingMicPolicy.clampYInSafeArea(
                        y = params.y,
                        screenHeight = safeArea.heightPx,
                        windowSize = size,
                        marginPx = margin,
                        insetTopPx = safeArea.insetTopPx,
                        insetBottomPx = safeArea.insetBottomPx,
                    )
                    yFraction = FloatingMicPolicy.yToFraction(
                        y = params.y,
                        screenHeight = safeArea.heightPx,
                        windowSize = size,
                        marginPx = margin,
                        insetTopPx = safeArea.insetTopPx,
                        insetBottomPx = safeArea.insetBottomPx,
                    )
                    try {
                        wm.updateViewLayout(view, params)
                        updateFeedbackPosition()
                    } catch (_: Exception) {
                    }
                    saveDockPreferences(params.x, params.y)
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
        if (!hasAnyActiveTarget() && !refreshEditorTargetBeforeAction()) {
            // The bubble is its own window, but removeOverlay() tears it down
            // too, so drop the overlay only once the message has had its
            // moment - and only if no editor appeared in the meantime.
            showFeedback("Place the cursor in a text field")
            mainHandler.postDelayed(
                Runnable { if (!hasAnyActiveTarget()) removeOverlay() },
                FEEDBACK_DURATION_MS,
            )
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
            } catch (_: Throwable) {
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
        hideFeedback()
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
                onState = { lifecycle ->
                    mainHandler.post {
                        when (lifecycle) {
                            LiveSessionState.SERVER_GOING_AWAY -> {
                                if (state == State.RECORDING) {
                                    stopDictation(LiveSessionState.SERVER_GOING_AWAY)
                                }
                            }
                            LiveSessionState.NETWORK_FAILURE -> {
                                if (state == State.RECORDING) {
                                    cancelCurrentWork()
                                }
                            }
                            else -> Unit
                        }
                    }
                },
                onInterim = { text ->
                    mainHandler.post {
                        if (state == State.RECORDING && liveGuard.isCurrent(sessionId)) {
                            liveLastInterim = text
                            renderLivePreview()
                        }
                    }
                },
                onFinal = { text ->
                    mainHandler.post {
                        if ((state == State.RECORDING || state == State.TRANSCRIBING) &&
                            liveGuard.isCurrent(sessionId)
                        ) {
                            liveFinalParts.append(text)
                            renderLivePreview()
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
        button?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        startPulse()
        capRunnable = Runnable {
            if (state == State.RECORDING) stopDictation(LiveSessionState.SESSION_LIMIT_REACHED)
        }.also { mainHandler.postDelayed(it, RECORD_CAP_MS) }
    }

    /**
     * Shows the latest replaceable hypothesis in the floating bubble only.
     * The editor is deliberately untouched until the final transcript is
     * committed after the user stops recording.
     */
    private fun renderLivePreview() {
        val preview = FloatingMicPolicy.mergeLivePreview(
            liveFinalParts.toString(),
            liveLastInterim,
        )
        if (preview.isNotEmpty()) showLivePreview(preview)
    }

    private fun stopDictation(reason: LiveSessionState = LiveSessionState.USER_STOPPED) {
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
                session.finish(reason)
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
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            showError("Got empty transcript — try again")
            return
        }
        val clean = ClipboardPolicy.truncateCommit(trimmed)
        maybeSaveTranscriptToHistory(clean)

        val stillEligible = refreshEditorTargetBeforeAction()
        val inserted = if (stillEligible) {
            insertAtCursor(clean)
        } else {
            false
        }
        if (inserted) {
            setState(State.IDLE)
            showFeedback("Inserted")
            button?.performHapticFeedback(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    HapticFeedbackConstants.CONFIRM
                } else {
                    HapticFeedbackConstants.KEYBOARD_TAP
                },
            )
        } else {
            copyToClipboardOnly(clean)
            setState(State.ERROR)
            showFeedback("Copied — tap to paste", durationMs = 6_000L)
        }
        armIdlePeekTimer()
        refreshFocusedEditor(eventSource = null, eventConfirmsSelection = false)
    }

    private fun maybeSaveTranscriptToHistory(clean: String) {
        if (!ClipboardHistoryActivity.isEnabled(this)) return
        if (!ClipboardPolicy.shouldCapture(clean)) return
        val maxItems = ClipboardHistoryActivity.maxCount(this)
        val ttl = ClipboardHistoryActivity.ttlMs(this)
        serviceScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val store = clipboardStore ?: ClipboardStore.forContext(this@FloatingMicAccessibilityService)
                        .also {
                            it.enabled = true
                            it.maxUnpinned = maxItems
                            it.ttlMs = ttl
                            it.load()
                            clipboardStore = it
                        }
                    store.enabled = true
                    store.maxUnpinned = maxItems
                    store.ttlMs = ttl
                    store.add(clean)
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                // Local history persistence is best-effort.
            }
        }
    }

    /**
     * 4-tier insertion cascade:
     * 1. Android 13+ `AccessibilityInputConnection.commitText` (preserves clipboard,
     *    works in WebView, Compose, Samsung Notes, Word, Notion, DeX, physical keyboards).
     * 2. Re-validated `AccessibilityNodeInfo` `ACTION_SET_TEXT` + cursor update.
     * 3. Clipboard + `ACTION_PASTE`.
     * 4. Android 13+ raw `TYPE_NULL` `commitText` (canvas/terminal fallback).
     */
    private fun insertAtCursor(text: String): Boolean {
        if (commitViaInputMethod(text, requireEditableInputType = true)) {
            return true
        }

        val node = targetNode
        if (node != null) {
            if (insertIntoNode(node, text)) return true
            if (pasteFromClipboard(text, node)) return true
        }

        return commitViaInputMethod(text, requireEditableInputType = false)
    }

    private fun commitViaInputMethod(
        text: String,
        requireEditableInputType: Boolean,
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        return FloatingEditorResolver.Api33Helper.commitText(
            service = this,
            text = text,
            ownPackageName = packageName(),
            requireEditableInputType = requireEditableInputType,
        )
    }

    private fun insertIntoNode(node: AccessibilityNodeInfo, text: String): Boolean {
        try {
            node.refresh()
            val pkg = node.packageName?.toString()
            if (node.isPassword ||
                FloatingMicPolicy.isPasswordInputType(node.inputType) ||
                pkg == packageName()
            ) {
                return false
            }
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val current = FloatingMicPolicy.textForInsertion(
                node.text?.toString(),
                node.hintText?.toString(),
                node.isShowingHintText,
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
        return false
    }

    private fun pasteFromClipboard(text: String, node: AccessibilityNodeInfo): Boolean {
        return try {
            node.refresh()
            val pkg = node.packageName?.toString()
            if (node.isPassword ||
                FloatingMicPolicy.isPasswordInputType(node.inputType) ||
                pkg == packageName()
            ) {
                return false
            }
            copyToClipboardOnly(text)
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        } catch (_: Exception) {
            false
        }
    }

    private fun copyToClipboardOnly(text: String) {
        try {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Voice IME", text))
        } catch (_: Exception) {
            // Best-effort clipboard copy.
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
        hideFeedback()
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
        button?.keepScreenOn = next == State.RECORDING || next == State.TRANSCRIBING
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
        if (state != State.IDLE || button == null || !hasAnyActiveTarget()) return
        val peekRunnable = Runnable { attemptPeek() }
        idlePeekRunnable = peekRunnable
        mainHandler.postDelayed(peekRunnable, FloatingMicPolicy.PEEK_DELAY_MS)
    }

    private fun cancelIdlePeekTimer() {
        idlePeekRunnable?.let(mainHandler::removeCallbacks)
        idlePeekRunnable = null
        peekRestoreRunnable?.let(mainHandler::removeCallbacks)
        peekRestoreRunnable = null
    }

    private fun attemptPeek() {
        val params = buttonParams ?: return
        if (state != State.IDLE || isPeeked || button == null) return
        isPeeked = true
        prePeekX = params.x
        val visible = dp(FloatingMicPolicy.PEEK_VISIBLE_DP)
        val edgeInset = if (dockRight) safeArea.insetRightPx else safeArea.insetLeftPx
        val target = FloatingMicPolicy.peekedX(
            params.x,
            safeArea.widthPx,
            params.width,
            visible,
            insetPx = edgeInset,
        )
        animateButtonX(target)
        val restore = Runnable {
            peekRestoreRunnable = null
            if (state == State.IDLE && isPeeked) restoreFromPeek()
        }
        peekRestoreRunnable = restore
        mainHandler.postDelayed(restore, FloatingMicPolicy.PEEK_RESTORE_DELAY_MS)
    }

    private fun restoreFromPeek() {
        if (!isPeeked) return
        peekRestoreRunnable?.let(mainHandler::removeCallbacks)
        peekRestoreRunnable = null
        val params = buttonParams ?: return
        val target = prePeekX ?: FloatingMicPolicy.dockedXInSafeArea(
            dockRight = dockRight,
            screenWidth = safeArea.widthPx,
            windowSize = params.width,
            marginPx = dp(MARGIN_DP),
            insetLeftPx = safeArea.insetLeftPx,
            insetRightPx = safeArea.insetRightPx,
        )
        isPeeked = false
        prePeekX = null
        animateButtonX(target)
        armIdlePeekTimer()
    }

    private fun animateButtonX(targetX: Int) {
        val params = buttonParams ?: return
        val view = button ?: return
        val wm = activeWindowManager()
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

    private fun hideFeedback() {
        feedbackHideRunnable?.let(mainHandler::removeCallbacks)
        feedbackHideRunnable = null
        feedback?.animate()?.cancel()
        feedback?.alpha = 0f
    }

    private fun showLivePreview(text: String) {
        mainHandler.post {
            val view = feedback ?: return@post
            view.text = FloatingMicPolicy.livePreviewTail(text, LIVE_PREVIEW_MAX_CHARS)
            view.visibility = View.VISIBLE
            view.animate().cancel()
            view.alpha = 1f
            updateFeedbackPosition()
            view.post { if (feedback === view) updateFeedbackPosition() }
            feedbackHideRunnable?.let(mainHandler::removeCallbacks)
            feedbackHideRunnable = null
        }
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
        val minX = margin + safeArea.insetLeftPx
        val maxX = (safeArea.widthPx - bubble.width - margin - safeArea.insetRightPx)
            .coerceAtLeast(minX)
        bubbleParams.x = (params.x - gap).coerceIn(minX, maxX)
        val opensUp = params.y + params.height / 2 > safeArea.heightPx / 2
        val minY = margin + safeArea.insetTopPx
        val maxY = (safeArea.heightPx - measuredHeight - margin - safeArea.insetBottomPx)
            .coerceAtLeast(minY)
        bubbleParams.y = if (opensUp) {
            (params.y - measuredHeight - margin).coerceIn(minY, maxY)
        } else {
            (params.y + params.height + margin).coerceIn(minY, maxY)
        }
        try {
            activeWindowManager().updateViewLayout(bubble, bubbleParams)
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
        val wm = activeWindowManager()
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
        mainHandler.removeCallbacks(discoveryStepRunnable)
        cancelCurrentWork()
        replaceTarget(null)
        hasActiveImeTarget = false
        removeOverlay()
        unbindOverlayDisplayCallbacks()
    }

    private fun dp(value: Int): Int = (value * density).toInt()
}
