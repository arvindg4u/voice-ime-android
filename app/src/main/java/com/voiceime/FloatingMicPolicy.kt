package com.voiceime

import android.text.InputType

/**
 * Pure policy and geometry for the system floating dictation button.
 *
 * Keeping these decisions out of [FloatingMicAccessibilityService] makes the
 * security-sensitive visibility rules, custom-editor eligibility, Samsung
 * multi-window/DeX focus rules, and rotation/inset-aware geometry easy to unit
 * test without a physical device or live accessibility binder.
 */
object FloatingMicPolicy {
    const val PEEK_VISIBLE_DP = 20
    const val PEEK_DELAY_MS = 4_000L
    /** Keep the edge peek brief, then restore the full button for discoverability. */
    const val PEEK_RESTORE_DELAY_MS = 700L
    const val PEEK_ANIMATION_MS = 220L
    const val TAP_THRESHOLD_DP = 10
    const val LONG_PRESS_MS = 500L

    /**
     * Progressive post-event discovery offsets (ms from transition start).
     * Tablet multi-window hand-offs, Samsung Honeyboard floating/handwriting
     * animations, and Chromium's async native accessibility initialization can
     * settle between 150 ms and ~1.8 s after the initial window/click event.
     */
    val FOCUS_DISCOVERY_OFFSETS_MS: LongArray = longArrayOf(80L, 220L, 500L, 1_000L, 1_800L)

    /** Upper bound on nodes visited when resolving virtual Compose/WebView children. */
    const val MAX_SUBTREE_SCAN_NODES = 250

    /** Maximum parent hops when the cursor sits inside a rich contenteditable child. */
    const val MAX_ANCESTOR_HOPS = 4

    private val EDITABLE_CLASS_SUFFIXES = arrayOf(
        "EditText",
        "AutoCompleteTextView",
        "MultiAutoCompleteTextView",
        "ExtractEditText",
    )

    private val HOST_CONTAINER_CLASS_SUFFIXES = arrayOf(
        "WebView",
        "AndroidComposeView",
        "FlutterView",
        "FlutterSurfaceView",
        "FlutterTextureView",
        "ContentView",
        "TextInputLayout",
        "ViewGroup",
        "FrameLayout",
        "LinearLayout",
        "RelativeLayout",
        "ConstraintLayout",
        "CoordinatorLayout",
        "ScrollView",
        "NestedScrollView",
        "RecyclerView",
        "ListView",
        "ViewPager",
        "ViewPager2",
    )

    /**
     * Detects every Android password InputType variation, including "visible
     * password" (after toggling a show-password eye icon, where
     * AccessibilityNodeInfo.isPassword often flips to false).
     */
    fun isPasswordInputType(inputType: Int): Boolean {
        val cls = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return when (cls) {
            InputType.TYPE_CLASS_TEXT ->
                variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            InputType.TYPE_CLASS_NUMBER ->
                variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
    }

    /** True when [inputType] declares a real non-password text/number/date editor. */
    fun hasEditableInputType(inputType: Int): Boolean {
        val cls = inputType and InputType.TYPE_MASK_CLASS
        if (cls == InputType.TYPE_NULL) return false
        if (isPasswordInputType(inputType)) return false
        return cls == InputType.TYPE_CLASS_TEXT ||
            cls == InputType.TYPE_CLASS_NUMBER ||
            cls == InputType.TYPE_CLASS_PHONE ||
            cls == InputType.TYPE_CLASS_DATETIME
    }

    /** Recognizes framework/Material EditText class names when custom views omit isEditable. */
    fun isEditableClassName(className: String?): Boolean {
        if (className.isNullOrBlank()) return false
        val simple = className.substringAfterLast('.')
        if (simple == "TextInputLayout") return false
        return EDITABLE_CLASS_SUFFIXES.any { suffix -> simple.endsWith(suffix) }
    }

    /**
     * Host containers (Compose's AndroidComposeView, WebView, FlutterView, layout
     * wrappers) frequently report input focus while their virtual descendant holds
     * the actual text editor. They must not be treated as leaf editors from
     * inputType alone, and should instead trigger a bounded child lookup.
     */
    fun isHostContainerClassName(className: String?): Boolean {
        if (className.isNullOrBlank()) return false
        val simple = className.substringAfterLast('.')
        return HOST_CONTAINER_CLASS_SUFFIXES.any { suffix -> simple.endsWith(suffix) }
    }

    /**
     * Full node-level eligibility contract covering standard EditText, Jetpack
     * Compose semantics nodes, WebView/Chromium inputs and contenteditable DOM
     * nodes, and custom views exposing ACTION_SET_TEXT, ACTION_IME_ENTER, or an
     * editable leaf InputType.
     */
    fun shouldShowForNode(
        isEditable: Boolean,
        isFocused: Boolean,
        isPassword: Boolean,
        packageName: String?,
        ownPackageName: String,
        inputFocusConfirmed: Boolean = false,
        isEnabled: Boolean = true,
        inputType: Int = InputType.TYPE_NULL,
        hasSetTextAction: Boolean = false,
        hasImeEnterAction: Boolean = false,
        className: String? = null,
        childCount: Int = 0,
    ): Boolean {
        if (!isEnabled) return false
        if (isPassword || isPasswordInputType(inputType)) return false
        if (!packageName.isNullOrEmpty() && packageName == ownPackageName) return false
        if (!isFocused && !inputFocusConfirmed) return false
        if (isEditable || hasSetTextAction || hasImeEnterAction || isEditableClassName(className)) {
            return true
        }
        return hasEditableInputType(inputType) &&
            !isHostContainerClassName(className) &&
            childCount == 0
    }

    /**
     * Baseline editor check preserved for callers that only have basic
     * AccessibilityNodeInfo flags.
     */
    fun shouldShowForEditor(
        isEditable: Boolean,
        isFocused: Boolean,
        isPassword: Boolean,
        packageName: String?,
        ownPackageName: String,
        inputFocusConfirmed: Boolean = false,
    ): Boolean = shouldShowForNode(
        isEditable = isEditable,
        isFocused = isFocused,
        isPassword = isPassword,
        packageName = packageName,
        ownPackageName = ownPackageName,
        inputFocusConfirmed = inputFocusConfirmed,
    )

    /**
     * Eligibility for Android 13+ (API 33+) AccessibilityService.InputMethod
     * sessions. A non-zero non-password EditorInfo.inputType comes directly from
     * InputMethodManagerService, catching WebView, Compose, Google Docs/Word,
     * Samsung DeX freeform windows, split-screen panes, and hardware-keyboard
     * cursors even when AccessibilityNodeInfo focus queries miss virtual nodes.
     *
     * Raw TYPE_NULL connections (used by some games/viewers for hardware keys,
     * and by Chromium when blurring an input onto page body) require additional
     * confirmation from an editable node or an open soft-keyboard window.
     */
    fun shouldShowForImeSession(
        inputStarted: Boolean,
        inputType: Int,
        packageName: String?,
        ownPackageName: String,
        passwordOrOwnAppVeto: Boolean = false,
        systemModalBlocking: Boolean = false,
        hasConfirmedNodeOrImeWindow: Boolean = false,
    ): Boolean {
        if (!inputStarted || passwordOrOwnAppVeto || systemModalBlocking) return false
        if (packageName.isNullOrBlank() || packageName == ownPackageName) return false
        if (isPasswordInputType(inputType)) return false
        if (hasEditableInputType(inputType)) return true
        return inputType == InputType.TYPE_NULL && hasConfirmedNodeOrImeWindow
    }

    /**
     * Distinguishes a true full-screen system shade (Notification Panel, Quick
     * Settings, Lockscreen) from small Samsung One UI system chrome windows
     * such as the persistent tablet Taskbar, Edge Panel handle, or multi-window
     * control header.
     */
    fun isFullScreenSystemOverlay(
        isActive: Boolean,
        isFocused: Boolean,
        windowWidth: Int,
        windowHeight: Int,
        screenWidth: Int,
        screenHeight: Int,
    ): Boolean {
        if (!isActive || !isFocused || screenWidth <= 0 || screenHeight <= 0) return false
        return windowWidth >= (screenWidth * 3) / 4 && windowHeight >= (screenHeight * 3) / 4
    }

    /**
     * AccessibilityNodeInfo implementations sometimes expose an editor hint
     * through text. Android 8+ also marks that case with isShowingHintText,
     * but some apps omit hintText while still setting the flag. Never copy
     * either representation of a placeholder into the user's field.
     */
    fun textForInsertion(
        text: String?,
        hintText: String?,
        showingHintText: Boolean = false,
    ): String? {
        if (text == null) return null
        return if (showingHintText || (!hintText.isNullOrEmpty() && text == hintText)) "" else text
    }

    /**
     * Merges the authoritative final prefix and the currently changing interim
     * hypothesis for a live preview. Interims can be either the whole running
     * hypothesis or only the next segment, so remove containment and the
     * longest suffix/prefix overlap before joining them.
     */
    fun mergeLivePreview(finalText: String, interimText: String): String {
        val final = finalText.trim()
        val interim = interimText.trim()
        if (final.isEmpty()) return interim
        if (interim.isEmpty()) return final
        if (final.contains(interim)) return final
        if (interim.contains(final)) return interim
        val max = minOf(final.length, interim.length)
        for (overlap in max downTo 1) {
            if (final.endsWith(interim.substring(0, overlap))) {
                return final + interim.substring(overlap)
            }
        }
        return "$final $interim"
    }

    /**
     * Keeps the newest part of a long live preview visible in the compact
     * bubble. The leading side rolls away as new words arrive.
     */
    fun livePreviewTail(text: String, maxChars: Int): String {
        val limit = maxChars.coerceAtLeast(0)
        val tail = text.takeLast(limit)
        if (text.length <= limit) return tail.trimStart()
        val boundary = tail.indexOfFirst { it.isWhitespace() }
        return if (boundary >= 0) tail.substring(boundary).trimStart() else tail.trimStart()
    }

    /** Keep the stop/cancel affordance reachable if focus disappears mid-session. */
    fun shouldKeepVisibleWhileBusy(isBusy: Boolean): Boolean = isBusy

    fun isRightDocked(x: Int, screenWidth: Int, windowSize: Int): Boolean =
        x + windowSize / 2 > screenWidth / 2

    /**
     * Returns the x coordinate that leaves [visiblePx] of the actual button on
     * screen. The window is allowed outside display bounds by
     * FLAG_LAYOUT_NO_LIMITS, which is what makes the peek animation real
     * rather than a no-op caused by WindowManager clamping.
     */
    fun peekedX(
        x: Int,
        screenWidth: Int,
        windowSize: Int,
        visiblePx: Int,
        insetPx: Int = 0,
    ): Int = if (isRightDocked(x, screenWidth, windowSize)) {
        screenWidth - visiblePx - insetPx.coerceAtLeast(0)
    } else {
        visiblePx - windowSize + insetPx.coerceAtLeast(0)
    }

    fun dockedX(x: Int, screenWidth: Int, windowSize: Int, marginPx: Int): Int =
        dockedXInSafeArea(
            dockRight = isRightDocked(x, screenWidth, windowSize),
            screenWidth = screenWidth,
            windowSize = windowSize,
            marginPx = marginPx,
        )

    fun dockedXInSafeArea(
        dockRight: Boolean,
        screenWidth: Int,
        windowSize: Int,
        marginPx: Int,
        insetLeftPx: Int = 0,
        insetRightPx: Int = 0,
    ): Int {
        val safeLeft = marginPx + insetLeftPx.coerceAtLeast(0)
        val safeRight =
            (screenWidth - windowSize - marginPx - insetRightPx.coerceAtLeast(0))
                .coerceAtLeast(safeLeft)
        return if (dockRight) safeRight else safeLeft
    }

    fun clampY(y: Int, screenHeight: Int, windowSize: Int, marginPx: Int): Int =
        clampYInSafeArea(y, screenHeight, windowSize, marginPx)

    fun clampYInSafeArea(
        y: Int,
        screenHeight: Int,
        windowSize: Int,
        marginPx: Int,
        insetTopPx: Int = 0,
        insetBottomPx: Int = 0,
    ): Int {
        val safeTop = marginPx + insetTopPx.coerceAtLeast(0)
        val safeBottom =
            (screenHeight - windowSize - marginPx - insetBottomPx.coerceAtLeast(0))
                .coerceAtLeast(safeTop)
        return y.coerceIn(safeTop, safeBottom)
    }

    /**
     * Converts a clamped pixel Y into a normalized [0f, 1f] vertical fraction
     * inside the safe area so rotating a tablet between landscape and portrait
     * preserves relative height instead of leaving the icon off-screen.
     */
    fun yToFraction(
        y: Int,
        screenHeight: Int,
        windowSize: Int,
        marginPx: Int,
        insetTopPx: Int = 0,
        insetBottomPx: Int = 0,
    ): Float {
        val safeTop = marginPx + insetTopPx.coerceAtLeast(0)
        val safeBottom =
            (screenHeight - windowSize - marginPx - insetBottomPx.coerceAtLeast(0))
                .coerceAtLeast(safeTop)
        val span = safeBottom - safeTop
        if (span <= 0) return 0.5f
        return ((y - safeTop).toFloat() / span.toFloat()).coerceIn(0f, 1f)
    }

    /** Restores a pixel Y inside the current display/orientation safe area from [fraction]. */
    fun yFromFraction(
        fraction: Float,
        screenHeight: Int,
        windowSize: Int,
        marginPx: Int,
        insetTopPx: Int = 0,
        insetBottomPx: Int = 0,
    ): Int {
        val safeTop = marginPx + insetTopPx.coerceAtLeast(0)
        val safeBottom =
            (screenHeight - windowSize - marginPx - insetBottomPx.coerceAtLeast(0))
                .coerceAtLeast(safeTop)
        val clampedFraction = fraction.coerceIn(0f, 1f)
        val target = safeTop + ((safeBottom - safeTop) * clampedFraction).toInt()
        return target.coerceIn(safeTop, safeBottom)
    }
}
