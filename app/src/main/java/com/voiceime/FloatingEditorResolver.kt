package com.voiceime

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.graphics.Insets
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Build
import android.text.InputType
import android.util.DisplayMetrics
import android.util.SparseArray
import android.view.Display
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.EditorInfo
import androidx.annotation.RequiresApi

/**
 * Resolves whether an editable non-password text cursor is currently active
 * across standard Views, Jetpack Compose semantics trees, Chromium/WebView
 * DOM/canvas editors, Samsung multi-window/split-screen/pop-up/App Bubbles,
 * Samsung DeX secondary displays, and hardware/S-Pen keyboard sessions.
 *
 * All API 30+ and API 33+ framework entry points live in dedicated
 * `@RequiresApi` nested classes so class verification and Android Lint pass
 * cleanly down to `minSdk = 26`.
 */
internal object FloatingEditorResolver {

    data class ImeSessionSnapshot(
        val inputStarted: Boolean = false,
        val inputType: Int = InputType.TYPE_NULL,
        val packageName: String? = null,
    )

    data class DisplaySafeArea(
        val widthPx: Int,
        val heightPx: Int,
        val insetLeftPx: Int = 0,
        val insetTopPx: Int = 0,
        val insetRightPx: Int = 0,
        val insetBottomPx: Int = 0,
    )

    data class Resolution(
        val shouldShow: Boolean,
        /** Freshly obtained copy owned by caller; null when backed solely by InputMethod. */
        val targetNode: AccessibilityNodeInfo?,
        val displayId: Int,
        val backedByImeSession: Boolean,
    )

    private data class CandidateWindow(
        val window: AccessibilityWindowInfo,
        val displayId: Int,
        val type: Int,
        val isFocused: Boolean,
        val isActive: Boolean,
        val layer: Int,
        val bounds: Rect,
    )

    private sealed class NodeOutcome {
        data object None : NodeOutcome()
        data object Veto : NodeOutcome()
        data class Match(val node: AccessibilityNodeInfo, val displayId: Int) : NodeOutcome()
    }

    /**
     * Reinforces critical accessibility flags at runtime in case the OEM
     * Settings cache parsed an older service description across an APK update.
     */
    fun reinforceServiceFlags(service: AccessibilityService) {
        try {
            val info = service.serviceInfo ?: return
            var desired = info.flags or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                desired = desired or Api33Helper.flagInputMethodEditor()
            }
            if (info.flags != desired) {
                info.flags = desired
                service.serviceInfo = info
            }
        } catch (_: Throwable) {
            // Best-effort reinforcement; XML metadata remains the primary source.
        }
    }

    /**
     * Evaluates all available focus signals (`eventSource`, global `FOCUS_INPUT`,
     * all interactive windows on all displays, and API 33+ `InputMethod` state).
     *
     * Does NOT recycle [eventSource]; callers retain ownership of [eventSource].
     * Always recycles [globalFocus] and any intermediate nodes/windows it fetches,
     * and returns a freshly `obtain`-ed [Resolution.targetNode] when matched.
     */
    fun resolve(
        service: AccessibilityService,
        eventSource: AccessibilityNodeInfo?,
        eventConfirmsSelection: Boolean,
        imeSnapshot: ImeSessionSnapshot,
        ownPackageName: String,
        fallbackSafeArea: DisplaySafeArea,
    ): Resolution {
        val candidateWindows = collectCandidateWindows(service)
        var globalFocus: AccessibilityNodeInfo? = null
        try {
            val hasSoftImeWindow = candidateWindows.any {
                it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD
            }

            // 1. Inspect the direct accessibility event source first (crucial for
            // Jetpack Compose virtual semantics nodes and S Pen / WebView taps where
            // AccessibilityService.findFocus(FOCUS_INPUT) returns the host View).
            if (eventSource != null) {
                val srcPkg = eventSource.packageName?.toString()
                val imeMatchesSource =
                    imeSnapshot.inputStarted &&
                        !srcPkg.isNullOrEmpty() &&
                        srcPkg == imeSnapshot.packageName &&
                        FloatingMicPolicy.hasEditableInputType(imeSnapshot.inputType)
                val selectionConfirmed =
                    eventConfirmsSelection && (eventSource.isFocused || hasSoftImeWindow || imeMatchesSource)
                when (
                    val out = evaluateRootCandidate(
                        node = eventSource,
                        ownPackageName = ownPackageName,
                        inputFocusConfirmed = selectionConfirmed,
                        fallbackDisplayId = nodeDisplayId(eventSource),
                    )
                ) {
                    is NodeOutcome.Veto -> return hiddenResolution()
                    is NodeOutcome.Match -> return Resolution(
                        shouldShow = true,
                        targetNode = out.node,
                        displayId = out.displayId,
                        backedByImeSession = imeSnapshot.inputStarted,
                    )
                    NodeOutcome.None -> Unit
                }
            }

            // 2. Inspect framework global input focus.
            globalFocus = try {
                service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            } catch (_: Throwable) {
                null
            }
            if (globalFocus != null) {
                when (
                    val out = evaluateRootCandidate(
                        node = globalFocus,
                        ownPackageName = ownPackageName,
                        inputFocusConfirmed = true,
                        fallbackDisplayId = nodeDisplayId(globalFocus),
                    )
                ) {
                    is NodeOutcome.Veto -> return hiddenResolution()
                    is NodeOutcome.Match -> return Resolution(
                        shouldShow = true,
                        targetNode = out.node,
                        displayId = out.displayId,
                        backedByImeSession = imeSnapshot.inputStarted,
                    )
                    NodeOutcome.None -> Unit
                }
            }

            // 3. Scan interactive windows across all displays (tablet split-screen,
            // freeform pop-up windows, Android 16/17 App Bubbles, Samsung DeX
            // external monitors, and inline notification replies).
            val orderedWindows = candidateWindows.sortedWith(
                compareByDescending<CandidateWindow> { it.isFocused }
                    .thenByDescending { it.isActive }
                    .thenByDescending { it.layer },
            )

            var systemModalBlocking = false
            for (cw in orderedWindows) {
                if (cw.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY ||
                    cw.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD ||
                    cw.type == AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER
                ) {
                    continue
                }

                if (cw.type == AccessibilityWindowInfo.TYPE_SYSTEM) {
                    if (cw.isFocused) {
                        val sysOut = evaluateWindow(cw, ownPackageName, imeSnapshot)
                        when (sysOut) {
                            is NodeOutcome.Veto -> return hiddenResolution()
                            is NodeOutcome.Match -> return Resolution(
                                shouldShow = true,
                                targetNode = sysOut.node,
                                displayId = sysOut.displayId,
                                backedByImeSession = imeSnapshot.inputStarted,
                            )
                            NodeOutcome.None -> {
                                if (FloatingMicPolicy.isFullScreenSystemOverlay(
                                        isActive = cw.isActive,
                                        isFocused = cw.isFocused,
                                        windowWidth = cw.bounds.width(),
                                        windowHeight = cw.bounds.height(),
                                        screenWidth = fallbackSafeArea.widthPx,
                                        screenHeight = fallbackSafeArea.heightPx,
                                    )
                                ) {
                                    systemModalBlocking = true
                                }
                            }
                        }
                    }
                    continue
                }

                if (cw.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
                when (val winOut = evaluateWindow(cw, ownPackageName, imeSnapshot)) {
                    is NodeOutcome.Veto -> return hiddenResolution()
                    is NodeOutcome.Match -> return Resolution(
                        shouldShow = true,
                        targetNode = winOut.node,
                        displayId = winOut.displayId,
                        backedByImeSession = imeSnapshot.inputStarted,
                    )
                    NodeOutcome.None -> Unit
                }
            }

            // 4. Android 13–17 AccessibilityService.InputMethod fallback: handles
            // Chrome/WebView before native AXTree initialization, canvas editors
            // (Google Docs, Termux, spreadsheets), Compose custom text inputs, and
            // Book Cover Keyboard sessions where AccessibilityNodeInfo omits focus.
            if (FloatingMicPolicy.shouldShowForImeSession(
                    inputStarted = imeSnapshot.inputStarted,
                    inputType = imeSnapshot.inputType,
                    packageName = imeSnapshot.packageName,
                    ownPackageName = ownPackageName,
                    passwordOrOwnAppVeto = false,
                    systemModalBlocking = systemModalBlocking,
                    hasConfirmedNodeOrImeWindow = hasSoftImeWindow,
                )
            ) {
                val targetDisplayId = resolveDisplayIdForPackage(
                    orderedWindows = orderedWindows,
                    packageName = imeSnapshot.packageName,
                )
                return Resolution(
                    shouldShow = true,
                    targetNode = null,
                    displayId = targetDisplayId,
                    backedByImeSession = true,
                )
            }

            return hiddenResolution()
        } finally {
            recycleQuietly(globalFocus)
            for (cw in candidateWindows) {
                recycleWindowQuietly(cw.window)
            }
        }
    }

    private fun hiddenResolution(): Resolution = Resolution(
        shouldShow = false,
        targetNode = null,
        displayId = Display.DEFAULT_DISPLAY,
        backedByImeSession = false,
    )

    private fun evaluateWindow(
        cw: CandidateWindow,
        ownPackageName: String,
        imeSnapshot: ImeSessionSnapshot,
    ): NodeOutcome {
        val root = try {
            cw.window.root
        } catch (_: Throwable) {
            null
        } ?: return NodeOutcome.None

        var winFocus: AccessibilityNodeInfo? = null
        try {
            val rootPkg = root.packageName?.toString()
            if (cw.isFocused && rootPkg == ownPackageName) {
                return NodeOutcome.Veto
            }

            winFocus = try {
                root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            } catch (_: Throwable) {
                null
            }
            if (winFocus != null) {
                val focusOut = evaluateRootCandidate(
                    node = winFocus,
                    ownPackageName = ownPackageName,
                    inputFocusConfirmed = true,
                    fallbackDisplayId = cw.displayId,
                )
                if (focusOut !is NodeOutcome.None) return focusOut
            }

            val imeMatchesWindow =
                imeSnapshot.inputStarted &&
                    !rootPkg.isNullOrEmpty() &&
                    rootPkg == imeSnapshot.packageName &&
                    FloatingMicPolicy.hasEditableInputType(imeSnapshot.inputType)
            if (cw.isFocused || imeMatchesWindow) {
                return scanSubtreeForFocusedEditor(
                    container = root,
                    ownPackageName = ownPackageName,
                    displayId = cw.displayId,
                )
            }
            return NodeOutcome.None
        } finally {
            recycleQuietly(winFocus)
            recycleQuietly(root)
        }
    }

    private fun evaluateRootCandidate(
        node: AccessibilityNodeInfo,
        ownPackageName: String,
        inputFocusConfirmed: Boolean,
        fallbackDisplayId: Int,
    ): NodeOutcome {
        if (isVetoNode(node, ownPackageName, inputFocusConfirmed)) {
            return NodeOutcome.Veto
        }
        if (isNodeEligible(node, ownPackageName, inputFocusConfirmed)) {
            val copy = copyNode(node) ?: return NodeOutcome.None
            return NodeOutcome.Match(copy, fallbackDisplayId)
        }

        // Climb up to MAX_ANCESTOR_HOPS parents when the selection/tap lands on an
        // inner <p>/<span> child inside a rich contenteditable container.
        val childConfirmedFocus = node.isFocused || inputFocusConfirmed
        var currentParent: AccessibilityNodeInfo? = try {
            node.parent
        } catch (_: Throwable) {
            null
        }
        var hops = 0
        while (currentParent != null && hops < FloatingMicPolicy.MAX_ANCESTOR_HOPS) {
            var nextParent: AccessibilityNodeInfo? = null
            try {
                if (isVetoNode(currentParent, ownPackageName, childConfirmedFocus)) {
                    return NodeOutcome.Veto
                }
                if (isNodeEligible(currentParent, ownPackageName, childConfirmedFocus)) {
                    val copy = copyNode(currentParent)
                    if (copy != null) {
                        return NodeOutcome.Match(copy, fallbackDisplayId)
                    }
                }
                hops++
                if (hops < FloatingMicPolicy.MAX_ANCESTOR_HOPS) {
                    nextParent = try {
                        currentParent.parent
                    } catch (_: Throwable) {
                        null
                    }
                }
            } finally {
                recycleQuietly(currentParent)
            }
            currentParent = nextParent
        }

        // If the candidate itself is a focused container (e.g. AndroidComposeView,
        // WebView, FlutterView, or TextInputLayout), search its subtree for the
        // virtual child holding input focus.
        if (childConfirmedFocus &&
            (node.childCount > 0 || FloatingMicPolicy.isHostContainerClassName(node.className?.toString()))
        ) {
            return scanSubtreeForFocusedEditor(
                container = node,
                ownPackageName = ownPackageName,
                displayId = fallbackDisplayId,
            )
        }

        return NodeOutcome.None
    }

    private fun scanSubtreeForFocusedEditor(
        container: AccessibilityNodeInfo,
        ownPackageName: String,
        displayId: Int,
    ): NodeOutcome {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        var visited = 0
        try {
            enqueueChildren(container, queue)
            while (queue.isNotEmpty() && visited < FloatingMicPolicy.MAX_SUBTREE_SCAN_NODES) {
                val child = queue.removeFirst()
                visited++
                try {
                    if (child.isFocused && isVetoNode(child, ownPackageName, inputFocusConfirmed = true)) {
                        return NodeOutcome.Veto
                    }
                    if (child.isFocused && isNodeEligible(child, ownPackageName, inputFocusConfirmed = true)) {
                        val copy = copyNode(child)
                        if (copy != null) {
                            return NodeOutcome.Match(copy, displayId)
                        }
                    }
                    if (visited + queue.size < FloatingMicPolicy.MAX_SUBTREE_SCAN_NODES && child.childCount > 0) {
                        enqueueChildren(child, queue)
                    }
                } finally {
                    recycleQuietly(child)
                }
            }
            return NodeOutcome.None
        } finally {
            while (queue.isNotEmpty()) {
                recycleQuietly(queue.removeFirst())
            }
        }
    }

    private fun enqueueChildren(
        parent: AccessibilityNodeInfo,
        queue: ArrayDeque<AccessibilityNodeInfo>,
    ) {
        val count = try {
            parent.childCount
        } catch (_: Throwable) {
            0
        }
        for (i in 0 until count) {
            if (queue.size >= FloatingMicPolicy.MAX_SUBTREE_SCAN_NODES) break
            val child = try {
                parent.getChild(i)
            } catch (_: Throwable) {
                null
            }
            if (child != null) {
                queue.addLast(child)
            }
        }
    }

    private fun isVetoNode(
        node: AccessibilityNodeInfo,
        ownPackageName: String,
        inputFocusConfirmed: Boolean,
    ): Boolean {
        if (!node.isFocused && !inputFocusConfirmed) return false
        val pkg = node.packageName?.toString()
        if (!pkg.isNullOrEmpty() && pkg == ownPackageName) return true
        return node.isPassword || FloatingMicPolicy.isPasswordInputType(node.inputType)
    }

    internal fun isNodeEligible(
        node: AccessibilityNodeInfo,
        ownPackageName: String,
        inputFocusConfirmed: Boolean,
    ): Boolean {
        val actions = try {
            node.actionList
        } catch (_: Throwable) {
            emptyList()
        }
        val hasSetText = actions.any {
            it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_TEXT.id
        }
        val hasImeEnter = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Api30Helper.hasImeEnterAction(actions)
        } else {
            false
        }
        return FloatingMicPolicy.shouldShowForNode(
            isEditable = node.isEditable,
            isFocused = node.isFocused,
            isPassword = node.isPassword,
            packageName = node.packageName?.toString(),
            ownPackageName = ownPackageName,
            inputFocusConfirmed = inputFocusConfirmed,
            isEnabled = node.isEnabled,
            inputType = node.inputType,
            hasSetTextAction = hasSetText,
            hasImeEnterAction = hasImeEnter,
            className = node.className?.toString(),
            childCount = node.childCount,
        )
    }

    private fun resolveDisplayIdForPackage(
        orderedWindows: List<CandidateWindow>,
        packageName: String?,
    ): Int {
        if (!packageName.isNullOrBlank()) {
            for (cw in orderedWindows) {
                if (cw.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
                val root = try {
                    cw.window.root
                } catch (_: Throwable) {
                    null
                } ?: continue
                try {
                    if (root.packageName?.toString() == packageName) {
                        return cw.displayId
                    }
                } finally {
                    recycleQuietly(root)
                }
            }
        }
        return orderedWindows
            .firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            ?.displayId
            ?: Display.DEFAULT_DISPLAY
    }

    private fun collectCandidateWindows(service: AccessibilityService): List<CandidateWindow> {
        val out = ArrayList<CandidateWindow>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val byDisplay = Api30Helper.windowsOnAllDisplays(service)
            if (byDisplay != null && byDisplay.size() > 0) {
                for (i in 0 until byDisplay.size()) {
                    val displayId = byDisplay.keyAt(i)
                    val list = byDisplay.valueAt(i) ?: continue
                    for (w in list) {
                        out.add(toCandidateWindow(w, displayId))
                    }
                }
                return out
            }
        }
        val singleDisplay = try {
            service.windows
        } catch (_: Throwable) {
            null
        } ?: return out
        for (w in singleDisplay) {
            val displayId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Api30Helper.windowDisplayId(w)
            } else {
                Display.DEFAULT_DISPLAY
            }
            out.add(toCandidateWindow(w, displayId))
        }
        return out
    }

    private fun toCandidateWindow(
        window: AccessibilityWindowInfo,
        displayId: Int,
    ): CandidateWindow {
        val rect = Rect()
        try {
            window.getBoundsInScreen(rect)
        } catch (_: Throwable) {
            // Leave empty rect if window vanished mid-query.
        }
        return CandidateWindow(
            window = window,
            displayId = displayId,
            type = window.type,
            isFocused = window.isFocused,
            isActive = window.isActive,
            layer = window.layer,
            bounds = rect,
        )
    }

    private fun nodeDisplayId(node: AccessibilityNodeInfo): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return Display.DEFAULT_DISPLAY
        val window = try {
            node.window
        } catch (_: Throwable) {
            null
        } ?: return Display.DEFAULT_DISPLAY
        return try {
            Api30Helper.windowDisplayId(window)
        } finally {
            recycleWindowQuietly(window)
        }
    }

    /** Creates an overlay-capable Context bound to [displayId] (supports DeX external monitors). */
    fun createOverlayContext(service: Context, displayId: Int): Context {
        val dm = service.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        val display = dm?.getDisplay(displayId) ?: dm?.getDisplay(Display.DEFAULT_DISPLAY)
        if (display != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Api30Helper.createOverlayWindowContext(service, display)?.let { return it }
        }
        if (display != null && display.displayId != Display.DEFAULT_DISPLAY) {
            try {
                return service.createDisplayContext(display)
            } catch (_: Throwable) {
                // Fall back to the service context below.
            }
        }
        return service
    }

    /** Reads exact display dimensions and system-bar / taskbar / cutout insets. */
    fun readDisplaySafeArea(windowManager: WindowManager?, fallbackContext: Context): DisplaySafeArea {
        if (windowManager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Api30Helper.readWindowMetricsSafeArea(windowManager)?.let { return it }
        }
        val dm: DisplayMetrics = fallbackContext.resources.displayMetrics
        return DisplaySafeArea(
            widthPx = dm.widthPixels,
            heightPx = dm.heightPixels,
        )
    }

    private fun copyNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? = try {
        @Suppress("DEPRECATION")
        AccessibilityNodeInfo.obtain(node)
    } catch (_: Throwable) {
        null
    }

    fun recycleQuietly(node: AccessibilityNodeInfo?) {
        if (node == null) return
        try {
            @Suppress("DEPRECATION")
            node.recycle()
        } catch (_: Throwable) {
            // Already recycled or managed by platform on newer SDKs.
        }
    }

    private fun recycleWindowQuietly(window: AccessibilityWindowInfo?) {
        if (window == null) return
        try {
            @Suppress("DEPRECATION")
            window.recycle()
        } catch (_: Throwable) {
            // Already recycled or managed by platform on newer SDKs.
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    internal object Api30Helper {
        fun hasImeEnterAction(actions: List<AccessibilityNodeInfo.AccessibilityAction>): Boolean =
            actions.any {
                it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id
            }

        fun windowsOnAllDisplays(
            service: AccessibilityService,
        ): SparseArray<List<AccessibilityWindowInfo>>? = try {
            service.windowsOnAllDisplays
        } catch (_: Throwable) {
            null
        }

        fun windowDisplayId(window: AccessibilityWindowInfo): Int = try {
            window.displayId
        } catch (_: Throwable) {
            Display.DEFAULT_DISPLAY
        }

        fun createOverlayWindowContext(base: Context, display: Display): Context? = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Api31Helper.createOverlayWindowContext(base, display)
            } else {
                base.createDisplayContext(display)
                    .createWindowContext(
                        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                        null,
                    )
            }
        } catch (_: Throwable) {
            null
        }

        fun readWindowMetricsSafeArea(wm: WindowManager): DisplaySafeArea? = try {
            val metrics = wm.currentWindowMetrics
            val bounds = metrics.bounds
            val insets: Insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
            )
            DisplaySafeArea(
                widthPx = bounds.width(),
                heightPx = bounds.height(),
                insetLeftPx = insets.left,
                insetTopPx = insets.top,
                insetRightPx = insets.right,
                insetBottomPx = insets.bottom,
            )
        } catch (_: Throwable) {
            null
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    internal object Api31Helper {
        fun createOverlayWindowContext(base: Context, display: Display): Context =
            base.createWindowContext(
                display,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                null,
            )
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    internal object Api33Helper {
        interface Listener {
            fun onImeSessionChanged(snapshot: ImeSessionSnapshot)
            fun onImeSelectionChanged()
        }

        fun flagInputMethodEditor(): Int = AccessibilityServiceInfo.FLAG_INPUT_METHOD_EDITOR

        fun createInputMethod(
            service: AccessibilityService,
            listener: Listener,
        ): android.accessibilityservice.InputMethod =
            object : android.accessibilityservice.InputMethod(service) {
                override fun onStartInput(attribute: EditorInfo, restarting: Boolean) {
                    super.onStartInput(attribute, restarting)
                    listener.onImeSessionChanged(
                        ImeSessionSnapshot(
                            inputStarted = true,
                            inputType = attribute.inputType,
                            packageName = attribute.packageName,
                        ),
                    )
                }

                override fun onFinishInput() {
                    super.onFinishInput()
                    listener.onImeSessionChanged(ImeSessionSnapshot(inputStarted = false))
                }

                override fun onUpdateSelection(
                    oldSelStart: Int,
                    oldSelEnd: Int,
                    newSelStart: Int,
                    newSelEnd: Int,
                    candidatesStart: Int,
                    candidatesEnd: Int,
                ) {
                    super.onUpdateSelection(
                        oldSelStart,
                        oldSelEnd,
                        newSelStart,
                        newSelEnd,
                        candidatesStart,
                        candidatesEnd,
                    )
                    listener.onImeSelectionChanged()
                }
            }

        /**
         * Reads the authoritative live state from [AccessibilityService.getInputMethod]
         * when available, falling back to [cached].
         */
        fun currentSnapshot(
            service: AccessibilityService,
            cached: ImeSessionSnapshot,
        ): ImeSessionSnapshot {
            val ime = try {
                service.inputMethod
            } catch (_: Throwable) {
                null
            } ?: return cached
            val started = try {
                ime.currentInputStarted
            } catch (_: Throwable) {
                return cached
            }
            if (!started) return ImeSessionSnapshot(inputStarted = false)
            val info = try {
                ime.currentInputEditorInfo
            } catch (_: Throwable) {
                null
            }
            return ImeSessionSnapshot(
                inputStarted = true,
                inputType = info?.inputType ?: cached.inputType,
                packageName = info?.packageName ?: cached.packageName,
            )
        }

        /**
         * Commits [text] directly through [AccessibilityInputConnection.commitText],
         * matching standard soft-keyboard insertion across WebView, Compose,
         * Samsung Notes, Microsoft Word, Notion, Obsidian, and DeX windows.
         *
         * When [requireEditableInputType] is true, skips raw `TYPE_NULL`
         * connections so `targetNode` (`ACTION_SET_TEXT` / `ACTION_PASTE`) gets
         * first shot; when false, serves as a last-chance commit before pure
         * clipboard fallback.
         */
        fun commitText(
            service: AccessibilityService,
            text: String,
            ownPackageName: String,
            requireEditableInputType: Boolean,
        ): Boolean {
            val ime = try {
                service.inputMethod
            } catch (_: Throwable) {
                null
            } ?: return false
            if (!ime.currentInputStarted) return false
            val info = ime.currentInputEditorInfo
            if (info != null) {
                if (info.packageName == ownPackageName) return false
                if (FloatingMicPolicy.isPasswordInputType(info.inputType)) return false
                if (requireEditableInputType && !FloatingMicPolicy.hasEditableInputType(info.inputType)) {
                    return false
                }
            } else if (requireEditableInputType) {
                return false
            }
            val ic = ime.currentInputConnection ?: return false
            return try {
                ic.commitText(text, 1, null)
                true
            } catch (_: Throwable) {
                false
            }
        }
    }
}
