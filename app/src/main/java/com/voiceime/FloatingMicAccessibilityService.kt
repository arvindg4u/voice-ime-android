package com.voiceime

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentCallbacks
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
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
import kotlin.math.hypot

/**
 * Optional system-wide floating microphone hosted inside an AccessibilityService.
 *
 * Play-policy boundary:
 * - Uses TYPE_ACCESSIBILITY_OVERLAY, never SYSTEM_ALERT_WINDOW.
 * - Subscribes only to focus/click/long-click/selection/window transitions and
 *   Android 13+ InputMethod start/finish callbacks; never text-changed or
 *   window-content-changed streams, and never logs or persists existing editor text.
 * - Shows only while a non-password, external-app text editor holds input focus
 *   (or while an active dictation/transcription session needs its stop/cancel UI).
 */
class FloatingMicAccessibilityService : AccessibilityService() {

    private enum class State { IDLE, RECORDING, TRANSCRIBING, ERROR }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val discardScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var settings: SettingsStore
    private var clipboardStore: ClipboardStore? = null
    private val llm = LlmClient()

    private var overlayContext: Context? = null
    private var overlayDisplayId: Int = Display.DEFAULT_DISPLAY
    private var windowManager: WindowManager? = null
    private var safeArea = FloatingEditorResolver.DisplaySafeArea(widthPx = 1080, heightPx = 1920)

    private var button: FloatingMicView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var previewView: TextView? = null
    private var previewLayoutParams: WindowManager.LayoutParams? = null
    private var attached = false
    private var previewAttached = false
    private var peeked = false
    private var peekAnimator: ValueAnimator? = null

    private var dockRight = true
    private var yFraction = 0.5f

    private var state = State.IDLE
    private var targetNode: AccessibilityNodeInfo? = null
    private var hasActiveImeTarget = false
    private var imeSessionSnapshot = FloatingEditorResolver.ImeSessionSnapshot()
    private var discoveryStepIndex = 0

    private var recorder: AudioRecorder? = null
    private var transcribeJob: Job? = null
    private var liveCommitJob: Job? = null
    private var liveStreamingSession: LiveSession? = null
    private val liveFinalParts = StringBuilder()
    private var liveLastInterim: String = ""
    private var keyCursor = 0

    private val peekRunnable = Runnable { animatePeek(true) }
    private val peekRestoreRunnable = Runnable { animatePeek(false) }
    private val clearErrorRunnable = Runnable {
        if (state == State.ERROR) {
            setState(State.IDLE)
            refreshFocus(eventSource = null, eventConfirmsSelection = false)
        }
    }
    private val livePreviewRunnable = Runnable { renderLivePreviewOnMain() }

    private val discoveryStepRunnable = object : Runnable {
        override fun run() {
            val offsets = FloatingMicPolicy.FOCUS_DISCOVERY_OFFSETS_MS
            if (discoveryStepIndex >= offsets.size) return
            val step = discoveryStepIndex
            discoveryStepIndex++
            refreshFocus(eventSource = null, eventConfirmsSelection = false)
            // Keep stepping if not yet attached, or once more at <=220ms to settle
            // multi-window / soft-keyboard insets; stop early once a concrete node is bound.
            val settled = attached && targetNode != null && offsets[step] >= 220L
            if (!settled && discoveryStepIndex < offsets.size) {
                val prevOffset = offsets[step]
                val nextDelay = (offsets[discoveryStepIndex] - prevOffset).coerceAtLeast(40L)
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

    override fun onServiceConnected() {
        super.onServiceConnected()
        connectedInstance = true
        settings = SettingsStore(this)
        FloatingEditorResolver.reinforceServiceFlags(this)
        bindOverlayDisplay(Display.DEFAULT_DISPLAY)
        loadSavedDockPreferences()
        refreshFocus(eventSource = null, eventConfirmsSelection = false)
        startDiscoveryWave()
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onCreateInputMethod(): android.accessibilityservice.InputMethod =
        FloatingEditorResolver.Api33Helper.createInputMethod(
            service = this,
            listener = object : FloatingEditorResolver.Api33Helper.Listener {
                override fun onImeSessionChanged(snapshot: FloatingEditorResolver.ImeSessionSnapshot) {
                    mainHandler.post {
                        imeSessionSnapshot = snapshot
                        refreshFocus(eventSource = null, eventConfirmsSelection = false)
                        if (snapshot.inputStarted) {
                            startDiscoveryWave()
                        }
                    }
                }

                override fun onImeSelectionChanged() {
                    mainHandler.post {
                        refreshFocus(eventSource = null, eventConfirmsSelection = false)
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
        val inspectSource = type == AccessibilityEvent.TYPE_VIEW_FOCUSED ||
            type == AccessibilityEvent.TYPE_VIEW_CLICKED ||
            type == AccessibilityEvent.TYPE_VIEW_LONG_CLICKED ||
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
            refreshFocus(
                eventSource = source,
                eventConfirmsSelection = type == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED,
            )
        } finally {
            FloatingEditorResolver.recycleQuietly(source)
        }

        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            type == AccessibilityEvent.TYPE_WINDOWS_CHANGED ||
            type == AccessibilityEvent.TYPE_VIEW_FOCUSED ||
            type == AccessibilityEvent.TYPE_VIEW_CLICKED ||
            type == AccessibilityEvent.TYPE_VIEW_LONG_CLICKED
        ) {
            startDiscoveryWave()
        }
    }

    override fun onInterrupt() {
        cancelWork()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        connectedInstance = false
        teardownServiceState()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        connectedInstance = false
        teardownServiceState()
        serviceScope.cancel()
        llm.shutdown()
        super.onDestroy()
    }

    private fun teardownServiceState() {
        mainHandler.removeCallbacks(discoveryStepRunnable)
        mainHandler.removeCallbacks(peekRunnable)
        mainHandler.removeCallbacks(peekRestoreRunnable)
        mainHandler.removeCallbacks(clearErrorRunnable)
        mainHandler.removeCallbacks(livePreviewRunnable)
        cancelWork()
        hideOverlay()
        unbindOverlayDisplayCallbacks()
        replaceTargetNode(null)
        hasActiveImeTarget = false
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

    private fun refreshFocus(
        eventSource: AccessibilityNodeInfo?,
        eventConfirmsSelection: Boolean,
    ) {
        if (!::settings.isInitialized) return
        safeArea = FloatingEditorResolver.readDisplaySafeArea(
            windowManager = windowManager,
            fallbackContext = overlayContext ?: this,
        )
        val resolution = FloatingEditorResolver.resolve(
            service = this,
            eventSource = eventSource,
            eventConfirmsSelection = eventConfirmsSelection,
            imeSnapshot = currentImeSnapshot(),
            ownPackageName = packageName,
            fallbackSafeArea = safeArea,
        )
        if (resolution.shouldShow) {
            replaceTargetNode(resolution.targetNode)
            hasActiveImeTarget = resolution.backedByImeSession
            ensureOverlayOnDisplay(resolution.displayId)
            showOverlay()
        } else {
            replaceTargetNode(null)
            hasActiveImeTarget = false
            if (!FloatingMicPolicy.shouldKeepVisibleWhileBusy(isBusy())) {
                hideOverlay()
            }
        }
    }

    /**
     * Re-evaluates focus right before recording starts or right before text
     * insertion. Returns true when either a valid [targetNode] or an eligible
     * Android 13+ `InputMethod` session is ready to receive dictated text.
     */
    private fun refreshEditorTarget(): Boolean {
        val resolution = FloatingEditorResolver.resolve(
            service = this,
            eventSource = null,
            eventConfirmsSelection = false,
            imeSnapshot = currentImeSnapshot(),
            ownPackageName = packageName,
            fallbackSafeArea = safeArea,
        )
        replaceTargetNode(resolution.targetNode)
        hasActiveImeTarget = resolution.shouldShow && resolution.backedByImeSession
        return resolution.shouldShow && (targetNode != null || hasActiveImeTarget)
    }

    private fun replaceTargetNode(next: AccessibilityNodeInfo?) {
        val old = targetNode
        targetNode = next
        if (old != null && old !== next) {
            FloatingEditorResolver.recycleQuietly(old)
        }
    }

    private fun bindOverlayDisplay(displayId: Int) {
        unbindOverlayDisplayCallbacks()
        val ctx = FloatingEditorResolver.createOverlayContext(this, displayId)
        overlayContext = ctx
        overlayDisplayId = displayId
        windowManager = ctx.getSystemService(WINDOW_SERVICE) as? WindowManager
            ?: getSystemService(WINDOW_SERVICE) as? WindowManager
        if (ctx !== this) {
            try {
                ctx.registerComponentCallbacks(overlayComponentCallbacks)
            } catch (_: Throwable) {
                // Non-fatal if OEM context rejects callback registration.
            }
        }
        safeArea = FloatingEditorResolver.readDisplaySafeArea(windowManager, ctx)
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
        if (overlayDisplayId == targetDisplayId) return
        if (isBusy()) return
        if (attached) {
            hideOverlay()
        }
        // Rebuild view instances against the new display's density/theme metrics.
        button = null
        layoutParams = null
        previewView = null
        previewLayoutParams = null
        bindOverlayDisplay(targetDisplayId)
    }

    private fun onDisplayGeometryChanged() {
        val wm = windowManager ?: return
        safeArea = FloatingEditorResolver.readDisplaySafeArea(wm, overlayContext ?: this)
        val lp = layoutParams ?: return
        val size = buttonSizePx()
        val margin = dp(EDGE_MARGIN_DP)
        lp.width = size
        lp.height = size
        peekAnimator?.cancel()
        peeked = false
        button?.peekProgress = 0f
        lp.x = FloatingMicPolicy.dockedXInSafeArea(
            dockRight = dockRight,
            screenWidth = safeArea.widthPx,
            windowSize = size,
            marginPx = margin,
            insetLeftPx = safeArea.insetLeftPx,
            insetRightPx = safeArea.insetRightPx,
        )
        lp.y = FloatingMicPolicy.yFromFraction(
            fraction = yFraction,
            screenHeight = safeArea.heightPx,
            windowSize = size,
            marginPx = margin,
            insetTopPx = safeArea.insetTopPx,
            insetBottomPx = safeArea.insetBottomPx,
        )
        if (attached) {
            updateOverlayLayout()
            updatePreviewPosition()
            schedulePeek()
        }
    }

    private fun loadSavedDockPreferences() {
        val prefs = getSharedPreferences(PREFS_OVERLAY, Context.MODE_PRIVATE)
        val size = buttonSizePx()
        val margin = dp(EDGE_MARGIN_DP)
        if (prefs.contains(PREF_DOCK_RIGHT) && prefs.contains(PREF_Y_FRACTION)) {
            dockRight = prefs.getBoolean(PREF_DOCK_RIGHT, true)
            yFraction = prefs.getFloat(PREF_Y_FRACTION, 0.5f).coerceIn(0f, 1f)
            return
        }
        // Migrate legacy v1.6.x pixel coordinates smoothly on first upgrade.
        if (prefs.contains(PREF_X) || prefs.contains(PREF_Y)) {
            val defaultX = safeArea.widthPx - size - margin
            val defaultY = (safeArea.heightPx - size) / 2
            val legacyX = prefs.getInt(PREF_X, defaultX)
            val legacyY = prefs.getInt(PREF_Y, defaultY)
            dockRight = FloatingMicPolicy.isRightDocked(legacyX, safeArea.widthPx, size)
            yFraction = FloatingMicPolicy.yToFraction(
                y = legacyY,
                screenHeight = safeArea.heightPx,
                windowSize = size,
                marginPx = margin,
                insetTopPx = safeArea.insetTopPx,
                insetBottomPx = safeArea.insetBottomPx,
            )
            savePositionPreferences()
        } else {
            dockRight = true
            yFraction = 0.5f
        }
    }

    private fun savePositionPreferences() {
        getSharedPreferences(PREFS_OVERLAY, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PREF_DOCK_RIGHT, dockRight)
            .putFloat(PREF_Y_FRACTION, yFraction)
            .apply()
    }

    private fun showOverlay() {
        val wm = windowManager ?: return
        safeArea = FloatingEditorResolver.readDisplaySafeArea(wm, overlayContext ?: this)
        if (button == null) {
            button = createButton()
            layoutParams = createLayoutParams()
        }
        if (!attached) {
            val lp = layoutParams ?: return
            val size = buttonSizePx()
            val margin = dp(EDGE_MARGIN_DP)
            lp.width = size
            lp.height = size
            lp.x = FloatingMicPolicy.dockedXInSafeArea(
                dockRight = dockRight,
                screenWidth = safeArea.widthPx,
                windowSize = size,
                marginPx = margin,
                insetLeftPx = safeArea.insetLeftPx,
                insetRightPx = safeArea.insetRightPx,
            )
            lp.y = FloatingMicPolicy.yFromFraction(
                fraction = yFraction,
                screenHeight = safeArea.heightPx,
                windowSize = size,
                marginPx = margin,
                insetTopPx = safeArea.insetTopPx,
                insetBottomPx = safeArea.insetBottomPx,
            )
            try {
                wm.addView(button, lp)
                attached = true
                peeked = false
                button?.peekProgress = 0f
                button?.visibility = View.VISIBLE
                schedulePeek()
            } catch (_: Exception) {
                attached = false
            }
        } else {
            button?.visibility = View.VISIBLE
            if (peeked && isBusy()) {
                animatePeek(false)
            } else if (!peeked) {
                schedulePeek()
            }
        }
    }

    private fun hideOverlay() {
        mainHandler.removeCallbacks(peekRunnable)
        mainHandler.removeCallbacks(peekRestoreRunnable)
        peekAnimator?.cancel()
        hideLivePreview()
        val wm = windowManager
        val view = button
        if (attached && wm != null && view != null) {
            try {
                wm.removeView(view)
            } catch (_: Exception) {
                // Window already torn down by framework.
            }
        }
        attached = false
        peeked = false
    }

    private fun createButton(): FloatingMicView {
        val viewContext = overlayContext ?: this
        val view = FloatingMicView(viewContext).apply {
            contentDescription = getString(R.string.ime_mic_description)
            isFocusable = false
        }
        attachTouchHandler(view)
        return view
    }

    private fun createLayoutParams(): WindowManager.LayoutParams {
        val size = buttonSizePx()
        val margin = dp(EDGE_MARGIN_DP)
        val x = FloatingMicPolicy.dockedXInSafeArea(
            dockRight = dockRight,
            screenWidth = safeArea.widthPx,
            windowSize = size,
            marginPx = margin,
            insetLeftPx = safeArea.insetLeftPx,
            insetRightPx = safeArea.insetRightPx,
        )
        val y = FloatingMicPolicy.yFromFraction(
            fraction = yFraction,
            screenHeight = safeArea.heightPx,
            windowSize = size,
            marginPx = margin,
            insetTopPx = safeArea.insetTopPx,
            insetBottomPx = safeArea.insetBottomPx,
        )
        return WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
            title = "VoiceImeFloatingMic"
        }
    }

    private fun attachTouchHandler(view: FloatingMicView) {
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        var longPressed = false
        val longPressRunnable = Runnable {
            if (!dragging) {
                longPressed = true
                hapticTap(view)
                onLongPress(view)
            }
        }

        view.setOnTouchListener { _, event ->
            val lp = layoutParams ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    mainHandler.removeCallbacks(peekRunnable)
                    mainHandler.removeCallbacks(peekRestoreRunnable)
                    if (peeked) animatePeek(false)
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = lp.x
                    startY = lp.y
                    dragging = false
                    longPressed = false
                    mainHandler.postDelayed(longPressRunnable, FloatingMicPolicy.LONG_PRESS_MS)
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!dragging && hypot(dx, dy) > dp(FloatingMicPolicy.TAP_THRESHOLD_DP)) {
                        dragging = true
                        mainHandler.removeCallbacks(longPressRunnable)
                    }
                    if (dragging) {
                        val size = buttonSizePx()
                        val margin = dp(EDGE_MARGIN_DP)
                        val minX = margin + safeArea.insetLeftPx
                        val maxX = (safeArea.widthPx - size - margin - safeArea.insetRightPx)
                            .coerceAtLeast(minX)
                        lp.x = (startX + dx.toInt()).coerceIn(minX, maxX)
                        lp.y = FloatingMicPolicy.clampYInSafeArea(
                            y = startY + dy.toInt(),
                            screenHeight = safeArea.heightPx,
                            windowSize = size,
                            marginPx = margin,
                            insetTopPx = safeArea.insetTopPx,
                            insetBottomPx = safeArea.insetBottomPx,
                        )
                        updateOverlayLayout()
                        updatePreviewPosition()
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    mainHandler.removeCallbacks(longPressRunnable)
                    if (dragging) {
                        snapToNearestEdge()
                        schedulePeek()
                    } else if (!longPressed) {
                        hapticTap(view)
                        onMicTapped()
                    }
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    mainHandler.removeCallbacks(longPressRunnable)
                    if (dragging) snapToNearestEdge()
                    schedulePeek()
                    true
                }

                else -> false
            }
        }
    }

    private fun snapToNearestEdge() {
        val lp = layoutParams ?: return
        val size = buttonSizePx()
        val margin = dp(EDGE_MARGIN_DP)
        dockRight = FloatingMicPolicy.isRightDocked(lp.x, safeArea.widthPx, size)
        val targetX = FloatingMicPolicy.dockedXInSafeArea(
            dockRight = dockRight,
            screenWidth = safeArea.widthPx,
            windowSize = size,
            marginPx = margin,
            insetLeftPx = safeArea.insetLeftPx,
            insetRightPx = safeArea.insetRightPx,
        )
        lp.y = FloatingMicPolicy.clampYInSafeArea(
            y = lp.y,
            screenHeight = safeArea.heightPx,
            windowSize = size,
            marginPx = margin,
            insetTopPx = safeArea.insetTopPx,
            insetBottomPx = safeArea.insetBottomPx,
        )
        yFraction = FloatingMicPolicy.yToFraction(
            y = lp.y,
            screenHeight = safeArea.heightPx,
            windowSize = size,
            marginPx = margin,
            insetTopPx = safeArea.insetTopPx,
            insetBottomPx = safeArea.insetBottomPx,
        )
        animateX(lp.x, targetX, 0f) {
            savePositionPreferences()
        }
    }

    private fun schedulePeek() {
        mainHandler.removeCallbacks(peekRunnable)
        mainHandler.removeCallbacks(peekRestoreRunnable)
        if (!attached || isBusy() || state == State.ERROR) return
        mainHandler.postDelayed(peekRunnable, FloatingMicPolicy.PEEK_DELAY_MS)
    }

    private fun animatePeek(toPeek: Boolean) {
        val lp = layoutParams ?: return
        if (!attached || peeked == toPeek) return
        if (toPeek && isBusy()) return
        val size = buttonSizePx()
        val margin = dp(EDGE_MARGIN_DP)
        val docked = FloatingMicPolicy.dockedXInSafeArea(
            dockRight = dockRight,
            screenWidth = safeArea.widthPx,
            windowSize = size,
            marginPx = margin,
            insetLeftPx = safeArea.insetLeftPx,
            insetRightPx = safeArea.insetRightPx,
        )
        val edgeInset = if (dockRight) safeArea.insetRightPx else safeArea.insetLeftPx
        val targetX = if (toPeek) {
            FloatingMicPolicy.peekedX(
                x = docked,
                screenWidth = safeArea.widthPx,
                windowSize = size,
                visiblePx = dp(FloatingMicPolicy.PEEK_VISIBLE_DP),
                insetPx = edgeInset,
            )
        } else {
            docked
        }
        peeked = toPeek
        animateX(lp.x, targetX, if (toPeek) 1f else 0f) {
            if (toPeek && attached && !isBusy() && state != State.ERROR) {
                mainHandler.removeCallbacks(peekRestoreRunnable)
                mainHandler.postDelayed(peekRestoreRunnable, FloatingMicPolicy.PEEK_RESTORE_DELAY_MS)
            }
        }
    }

    private fun animateX(fromX: Int, toX: Int, targetPeek: Float, onEnd: (() -> Unit)? = null) {
        val lp = layoutParams ?: return
        val startPeek = button?.peekProgress ?: 0f
        peekAnimator?.cancel()
        peekAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = FloatingMicPolicy.PEEK_ANIMATION_MS
            addUpdateListener { anim ->
                val t = anim.animatedValue as Float
                lp.x = (fromX + (toX - fromX) * t).toInt()
                button?.peekProgress = startPeek + (targetPeek - startPeek) * t
                updateOverlayLayout()
                updatePreviewPosition()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    onEnd?.invoke()
                }
            })
            start()
        }
    }

    private fun updateOverlayLayout() {
        val wm = windowManager ?: return
        val view = button ?: return
        val lp = layoutParams ?: return
        if (!attached) return
        try {
            wm.updateViewLayout(view, lp)
        } catch (_: Exception) {
            attached = false
        }
    }

    private fun ensurePreviewView(): TextView {
        previewView?.let { return it }
        val viewContext = overlayContext ?: this
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(PREVIEW_CORNER_DP).toFloat()
            setColor(Color.parseColor("#EE161622"))
            setStroke(dp(1), Color.parseColor("#33FFFFFF"))
        }
        return TextView(viewContext).apply {
            background = bg
            setTextColor(Color.parseColor("#F5F7FA"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 2
            maxWidth = dp(PREVIEW_MAX_WIDTH_DP)
            ellipsize = TextUtils.TruncateAt.START
            setPadding(dp(12), dp(8), dp(12), dp(8))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            isFocusable = false
            isClickable = false
        }.also { previewView = it }
    }

    private fun createPreviewLayoutParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            title = "VoiceImeFloatingPreview"
        }

    private fun applyPreviewPosition(lp: WindowManager.LayoutParams) {
        val micLp = layoutParams ?: return
        val size = buttonSizePx()
        val gap = dp(PREVIEW_GAP_DP)
        val margin = dp(EDGE_MARGIN_DP)
        lp.y = FloatingMicPolicy.clampYInSafeArea(
            y = micLp.y + dp(4),
            screenHeight = safeArea.heightPx,
            windowSize = size,
            marginPx = margin,
            insetTopPx = safeArea.insetTopPx,
            insetBottomPx = safeArea.insetBottomPx,
        )
        if (FloatingMicPolicy.isRightDocked(micLp.x, safeArea.widthPx, size)) {
            lp.gravity = Gravity.TOP or Gravity.END
            lp.x = (safeArea.widthPx - micLp.x + gap).coerceAtLeast(margin + safeArea.insetRightPx)
        } else {
            lp.gravity = Gravity.TOP or Gravity.START
            lp.x = (micLp.x + size + gap).coerceAtLeast(margin + safeArea.insetLeftPx)
        }
    }

    private fun updatePreviewPosition() {
        if (!previewAttached) return
        val wm = windowManager ?: return
        val view = previewView ?: return
        val lp = previewLayoutParams ?: return
        applyPreviewPosition(lp)
        try {
            wm.updateViewLayout(view, lp)
        } catch (_: Exception) {
            previewAttached = false
        }
    }

    private fun postLivePreviewUpdate() {
        mainHandler.removeCallbacks(livePreviewRunnable)
        mainHandler.post(livePreviewRunnable)
    }

    private fun renderLivePreviewOnMain() {
        if (state != State.RECORDING && state != State.TRANSCRIBING) {
            hideLivePreview()
            return
        }
        val merged = FloatingMicPolicy.mergeLivePreview(liveFinalParts.toString(), liveLastInterim)
        val display = FloatingMicPolicy.livePreviewTail(merged, PREVIEW_TAIL_CHARS)
        if (display.isBlank() || !attached) {
            hideLivePreview()
            return
        }
        val wm = windowManager ?: return
        val view = ensurePreviewView()
        view.text = display
        val lp = previewLayoutParams ?: createPreviewLayoutParams().also { previewLayoutParams = it }
        applyPreviewPosition(lp)
        if (!previewAttached) {
            try {
                wm.addView(view, lp)
                previewAttached = true
            } catch (_: Exception) {
                previewAttached = false
            }
        } else {
            updatePreviewPosition()
        }
    }

    private fun hideLivePreview() {
        mainHandler.removeCallbacks(livePreviewRunnable)
        val wm = windowManager
        val view = previewView
        if (previewAttached && wm != null && view != null) {
            try {
                wm.removeView(view)
            } catch (_: Exception) {
                // Window already torn down by framework.
            }
        }
        previewAttached = false
    }

    private fun onMicTapped() {
        when (state) {
            State.IDLE, State.ERROR -> {
                if (!refreshEditorTarget()) {
                    hideOverlay()
                    return
                }
                startDictation()
            }

            State.RECORDING -> stopAndTranscribe()
            State.TRANSCRIBING -> Unit
        }
    }

    private fun onLongPress(anchor: View) {
        if (isBusy()) {
            cancelWork()
            Toast.makeText(this, R.string.ime_cancel, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val popup = PopupMenu(overlayContext ?: this, anchor)
            popup.menu.add(0, MENU_SETTINGS, 0, R.string.ime_settings)
            popup.setOnMenuItemClickListener { item ->
                if (item.itemId == MENU_SETTINGS) openSettings()
                true
            }
            popup.setOnDismissListener { schedulePeek() }
            popup.show()
        } catch (_: Exception) {
            openSettings()
        }
    }

    private fun openSettings() {
        val intent = Intent(this, SettingsActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    private fun startDictation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            val intent = Intent(this, MicPermissionActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY)
            }
            startActivity(intent)
            return
        }
        val apiKeys = settings.apiKeys
        val baseUrl = settings.baseUrl
        if (apiKeys.isEmpty() || baseUrl.isEmpty()) {
            showError(getString(R.string.ime_error_no_key))
            openSettings()
            return
        }
        val model = settings.model
        val customPrompt = settings.customPrompt
        val smartMode = settings.smartMode

        cancelWork()
        val rec = AudioRecorder()
        val useLive = LlmClient.isLiveModel(model)
        if (useLive) {
            startLiveSession(rec, apiKeys, baseUrl, model, customPrompt, smartMode)
        }
        try {
            rec.start { amp ->
                mainHandler.post { button?.level = amp }
            }
        } catch (_: Exception) {
            liveStreamingSession?.close()
            liveStreamingSession = null
            rec.discard()
            showError(getString(R.string.ime_error_mic))
            return
        }
        recorder = rec
        setState(State.RECORDING)
    }

    private fun startLiveSession(
        rec: AudioRecorder,
        apiKeys: List<String>,
        baseUrl: String,
        model: String,
        customPrompt: String,
        smartMode: Boolean,
    ) {
        liveFinalParts.setLength(0)
        liveLastInterim = ""
        val key = apiKeys[keyCursor.coerceIn(0, apiKeys.size - 1)]
        val session = llm.openLiveSession(
            apiKey = key,
            baseUrl = baseUrl,
            model = model,
            customPrompt = customPrompt,
            smartMode = smartMode,
            listener = object : LiveSession.Listener {
                override fun onReady() = Unit

                override fun onTranscript(text: String, finished: Boolean) {
                    if (finished) {
                        if (text.isNotEmpty()) {
                            if (liveFinalParts.isNotEmpty() &&
                                !liveFinalParts.endsWith(" ") &&
                                !text.startsWith(" ")
                            ) {
                                liveFinalParts.append(' ')
                            }
                            liveFinalParts.append(text)
                        }
                        liveLastInterim = ""
                    } else {
                        liveLastInterim = text
                    }
                    postLivePreviewUpdate()
                }

                override fun onTurnComplete() = Unit
                override fun onError(message: String, rateLimited: Boolean) = Unit
            },
        )
        liveStreamingSession = session
        rec.pcmChunkListener = { chunk, len -> session.offerPcm(chunk, len) }
    }

    private fun stopAndTranscribe() {
        val rec = recorder ?: return
        recorder = null
        val liveSession = liveStreamingSession
        liveStreamingSession = null
        setState(State.TRANSCRIBING)

        val apiKeys = settings.apiKeys
        val baseUrl = settings.baseUrl
        val model = settings.model
        val customPrompt = settings.customPrompt
        val smartMode = settings.smartMode
        val start = keyCursor

        if (liveSession != null) {
            liveSession.detach()
            liveCommitJob = serviceScope.launch {
                val stopResult = try {
                    withContext(Dispatchers.IO) { rec.stop() }
                } catch (_: Exception) {
                    liveSession.close()
                    showError(getString(R.string.ime_error_no_audio))
                    return@launch
                }
                if (stopResult.bytes.size <= 44) {
                    liveSession.close()
                    showError(getString(R.string.ime_error_no_audio))
                    return@launch
                }
                if (!stopResult.speechDetected) {
                    liveSession.close()
                    showError(getString(R.string.ime_error_no_speech))
                    return@launch
                }
                val liveText = try {
                    withContext(Dispatchers.IO) { liveSession.finishAndAwait() }
                } catch (ce: CancellationException) {
                    liveSession.close()
                    throw ce
                } catch (_: Exception) {
                    null
                }
                if (!liveText.isNullOrBlank()) {
                    if (apiKeys.isNotEmpty()) keyCursor = (start + 1) % apiKeys.size
                    deliverTranscript(liveText)
                    return@launch
                }
                runBatchFallback(stopResult.bytes, apiKeys, baseUrl, model, start, customPrompt, smartMode)
            }
            return
        }

        transcribeJob = serviceScope.launch {
            val stopResult = try {
                withContext(Dispatchers.IO) { rec.stop() }
            } catch (_: Exception) {
                showError(getString(R.string.ime_error_no_audio))
                return@launch
            }
            if (stopResult.bytes.size <= 44) {
                showError(getString(R.string.ime_error_no_audio))
                return@launch
            }
            if (!stopResult.speechDetected) {
                showError(getString(R.string.ime_error_no_speech))
                return@launch
            }
            runBatchFallback(stopResult.bytes, apiKeys, baseUrl, model, start, customPrompt, smartMode)
        }
    }

    private suspend fun runBatchFallback(
        wav: ByteArray,
        apiKeys: List<String>,
        baseUrl: String,
        model: String,
        start: Int,
        customPrompt: String,
        smartMode: Boolean,
    ) {
        try {
            val (transcript, usedIndex) = llm.transcribe(
                wav,
                apiKeys,
                baseUrl,
                model,
                start,
                customPrompt,
                smartMode,
            )
            if (apiKeys.isNotEmpty()) keyCursor = (usedIndex + 1) % apiKeys.size
            deliverTranscript(transcript)
        } catch (e: TranscribeException) {
            showError(e.message ?: getString(R.string.ime_error_network))
        }
    }

    private fun deliverTranscript(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            showError(getString(R.string.ime_error_empty))
            return
        }
        val clean = ClipboardPolicy.truncateCommit(trimmed)
        maybeSaveTranscriptToHistory(clean)

        val stillEligible = refreshEditorTarget()
        val inserted = if (stillEligible) {
            commitViaInputMethod(clean, requireEditableInputType = true) ||
                insertIntoNode(targetNode, clean) ||
                pasteIntoNode(targetNode, clean) ||
                commitViaInputMethod(clean, requireEditableInputType = false)
        } else {
            false
        }
        if (!inserted) {
            copyToClipboard(clean)
            Toast.makeText(this, R.string.ime_copied_paste, Toast.LENGTH_SHORT).show()
        } else if (clean.length != trimmed.length) {
            Toast.makeText(this, R.string.ime_commit_truncated, Toast.LENGTH_LONG).show()
        }
        setState(State.IDLE)
        hapticConfirm()
        refreshFocus(eventSource = null, eventConfirmsSelection = false)
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

    private fun commitViaInputMethod(
        text: String,
        requireEditableInputType: Boolean,
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        return FloatingEditorResolver.Api33Helper.commitText(
            service = this,
            text = text,
            ownPackageName = packageName,
            requireEditableInputType = requireEditableInputType,
        )
    }

    /**
     * Splices [text] into [node] at the current selection using ACTION_SET_TEXT,
     * then places the cursor after the inserted text. Re-validates password and
     * own-package boundaries after `node.refresh()`.
     */
    private fun insertIntoNode(node: AccessibilityNodeInfo?, text: String): Boolean {
        if (node == null) return false
        try {
            node.refresh()
        } catch (_: Exception) {
            // Continue with the best snapshot we have.
        }
        val pkg = node.packageName?.toString()
        if (node.isPassword ||
            FloatingMicPolicy.isPasswordInputType(node.inputType) ||
            pkg == packageName
        ) {
            return false
        }
        val hasSetText = try {
            node.actionList.any {
                it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_TEXT.id
            }
        } catch (_: Throwable) {
            false
        }
        if (!node.isEditable && !hasSetText && !FloatingMicPolicy.isEditableClassName(node.className?.toString())) {
            return false
        }

        val current = FloatingMicPolicy.textForInsertion(
            text = node.text?.toString(),
            hintText = node.hintText?.toString(),
            showingHintText = node.isShowingHintText,
        ) ?: ""
        val selStart = node.textSelectionStart
        val selEnd = node.textSelectionEnd
        val start: Int
        val end: Int
        if (selStart >= 0 && selEnd >= 0 && selStart <= current.length && selEnd <= current.length) {
            start = minOf(selStart, selEnd)
            end = maxOf(selStart, selEnd)
        } else {
            start = current.length
            end = current.length
        }
        val replacement = current.substring(0, start) + text + current.substring(end)
        val setArgs = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                replacement,
            )
        }
        val applied = try {
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setArgs)
        } catch (_: Throwable) {
            false
        }
        if (!applied) return false
        val newCursor = start + text.length
        val selArgs = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, newCursor)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, newCursor)
        }
        try {
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selArgs)
        } catch (_: Throwable) {
            // Cursor placement is best-effort once text was applied.
        }
        return true
    }

    private fun pasteIntoNode(node: AccessibilityNodeInfo?, text: String): Boolean {
        copyToClipboard(text)
        if (node == null) return false
        try {
            node.refresh()
        } catch (_: Throwable) {
            // Ignore refresh errors and inspect current node snapshot.
        }
        val pkg = node.packageName?.toString()
        if (node.isPassword ||
            FloatingMicPolicy.isPasswordInputType(node.inputType) ||
            pkg == packageName
        ) {
            return false
        }
        return try {
            node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        } catch (_: Throwable) {
            false
        }
    }

    private fun copyToClipboard(text: String) {
        try {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("voice-ime", text))
        } catch (_: Exception) {
            // Nothing else we can safely do without an active InputConnection.
        }
    }

    private fun showError(message: String) {
        setState(State.ERROR)
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        mainHandler.removeCallbacks(clearErrorRunnable)
        mainHandler.postDelayed(clearErrorRunnable, ERROR_DISPLAY_MS)
    }

    private fun cancelWork() {
        mainHandler.removeCallbacks(clearErrorRunnable)
        hideLivePreview()
        transcribeJob?.cancel()
        transcribeJob = null
        liveCommitJob?.cancel()
        liveCommitJob = null
        llm.cancel()
        val attachedSession = liveStreamingSession
        liveStreamingSession = null
        if (attachedSession != null && !attachedSession.isDetached()) {
            try {
                attachedSession.close()
            } catch (_: Exception) {
                // Already closed.
            }
        }
        liveFinalParts.setLength(0)
        liveLastInterim = ""
        val rec = recorder
        recorder = null
        if (rec != null) {
            discardScope.launch {
                try {
                    rec.discard()
                } catch (_: Exception) {
                    // Best-effort mic release.
                }
            }
        }
        if (state != State.IDLE) {
            setState(State.IDLE)
        }
    }

    private fun setState(next: State) {
        state = next
        if (next != State.RECORDING && next != State.TRANSCRIBING) {
            hideLivePreview()
            liveFinalParts.setLength(0)
            liveLastInterim = ""
        }
        val keepAwake = next == State.RECORDING || next == State.TRANSCRIBING
        button?.keepScreenOn = keepAwake
        button?.visualState = when (next) {
            State.IDLE -> FloatingMicView.VisualState.IDLE
            State.RECORDING -> FloatingMicView.VisualState.RECORDING
            State.TRANSCRIBING -> FloatingMicView.VisualState.TRANSCRIBING
            State.ERROR -> FloatingMicView.VisualState.ERROR
        }
        button?.contentDescription = when (next) {
            State.RECORDING -> getString(R.string.ime_mic_stop_description)
            State.TRANSCRIBING -> getString(R.string.ime_status_uploading)
            else -> getString(R.string.ime_mic_description)
        }
        if (next == State.RECORDING || next == State.TRANSCRIBING || next == State.ERROR) {
            mainHandler.removeCallbacks(peekRunnable)
            mainHandler.removeCallbacks(peekRestoreRunnable)
            if (peeked) animatePeek(false)
        } else {
            schedulePeek()
        }
    }

    private fun isBusy(): Boolean = state == State.RECORDING || state == State.TRANSCRIBING

    private fun hapticTap(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    private fun hapticConfirm() {
        val view = button ?: return
        val constant = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.CONFIRM
        } else {
            HapticFeedbackConstants.KEYBOARD_TAP
        }
        view.performHapticFeedback(constant)
    }

    private fun buttonSizePx(): Int = dp(FloatingMicView.WINDOW_DP)

    private fun dp(value: Int): Int {
        val dm = (overlayContext ?: this).resources.displayMetrics
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            dm,
        ).toInt()
    }

    companion object {
        @Volatile
        private var connectedInstance = false

        /**
         * True when Android's AccessibilityManagerService has an active live
         * binder connection to [FloatingMicAccessibilityService]. Used by
         * [SettingsActivity] to diagnose Samsung One UI Deep Sleep / stalled
         * accessibility bindings where Settings.Secure still marks the service
         * enabled while the process was stopped in the background.
         */
        fun isServiceConnected(): Boolean = connectedInstance

        private const val EDGE_MARGIN_DP = 6
        private const val PREVIEW_GAP_DP = 8
        private const val PREVIEW_CORNER_DP = 14
        private const val PREVIEW_MAX_WIDTH_DP = 232
        private const val PREVIEW_TAIL_CHARS = 76
        private const val ERROR_DISPLAY_MS = 2_600L
        private const val MENU_SETTINGS = 1
        private const val PREFS_OVERLAY = "voice_ime_floating_overlay"
        private const val PREF_X = "x"
        private const val PREF_Y = "y"
        private const val PREF_DOCK_RIGHT = "dock_right"
        private const val PREF_Y_FRACTION = "y_fraction"
    }
}
