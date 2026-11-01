package com.voiceime

import android.text.InputType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FloatingMicPolicyTest {

    @Test
    fun `shows only for focused non-password external editable fields`() {
        assertTrue(
            FloatingMicPolicy.shouldShowForEditor(
                isEditable = true,
                isFocused = true,
                isPassword = false,
                packageName = "com.whatsapp",
                ownPackageName = "com.voiceime",
            ),
        )
    }

    @Test
    fun `shows for editable input focus even when node isFocused lags`() {
        assertTrue(
            FloatingMicPolicy.shouldShowForEditor(
                isEditable = true,
                isFocused = false,
                isPassword = false,
                packageName = "com.samsung.android.app.notes",
                ownPackageName = "com.voiceime",
                inputFocusConfirmed = true,
            ),
        )
    }

    @Test
    fun `hides for stylus hover or unfocused view without confirmed input focus`() {
        assertFalse(
            FloatingMicPolicy.shouldShowForEditor(
                isEditable = true,
                isFocused = false,
                isPassword = false,
                packageName = "com.samsung.android.app.notes",
                ownPackageName = "com.voiceime",
                inputFocusConfirmed = false,
            ),
        )
    }

    @Test
    fun `hides for password fields across all inputType variations`() {
        assertFalse(
            FloatingMicPolicy.shouldShowForEditor(
                isEditable = true,
                isFocused = true,
                isPassword = true,
                packageName = "com.whatsapp",
                ownPackageName = "com.voiceime",
            ),
        )

        val passwordInputTypes = listOf(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD,
        )
        for (pwType in passwordInputTypes) {
            assertTrue(FloatingMicPolicy.isPasswordInputType(pwType))
            assertFalse(FloatingMicPolicy.hasEditableInputType(pwType))
            assertFalse(
                FloatingMicPolicy.shouldShowForNode(
                    isEditable = true,
                    isFocused = true,
                    isPassword = false,
                    inputType = pwType,
                    packageName = "com.android.chrome",
                    ownPackageName = "com.voiceime",
                ),
            )
            assertFalse(
                FloatingMicPolicy.shouldShowForImeSession(
                    inputStarted = true,
                    inputType = pwType,
                    packageName = "com.android.chrome",
                    ownPackageName = "com.voiceime",
                ),
            )
        }
    }

    @Test
    fun `shows for custom and web editors with setText or imeEnter or leaf inputType`() {
        // Compose / WebView semantics node exposing ACTION_SET_TEXT without isEditable
        assertTrue(
            FloatingMicPolicy.shouldShowForNode(
                isEditable = false,
                isFocused = true,
                isPassword = false,
                hasSetTextAction = true,
                packageName = "notion.id",
                ownPackageName = "com.voiceime",
            ),
        )
        // Custom editor exposing ACTION_IME_ENTER
        assertTrue(
            FloatingMicPolicy.shouldShowForNode(
                isEditable = false,
                isFocused = true,
                isPassword = false,
                hasImeEnterAction = true,
                packageName = "md.obsidian",
                ownPackageName = "com.voiceime",
            ),
        )
        // Leaf custom canvas editor exposing editable InputType
        assertTrue(
            FloatingMicPolicy.shouldShowForNode(
                isEditable = false,
                isFocused = true,
                isPassword = false,
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE,
                className = "com.custom.CanvasEditorView",
                childCount = 0,
                packageName = "com.microsoft.office.word",
                ownPackageName = "com.voiceime",
            ),
        )
        // Host container (WebView / AndroidComposeView) with children must NOT match directly
        assertFalse(
            FloatingMicPolicy.shouldShowForNode(
                isEditable = false,
                isFocused = true,
                isPassword = false,
                inputType = InputType.TYPE_CLASS_TEXT,
                className = "android.webkit.WebView",
                childCount = 3,
                packageName = "com.android.chrome",
                ownPackageName = "com.voiceime",
            ),
        )
    }

    @Test
    fun `ime session eligibility handles tablet webview compose hardware keyboard and blur`() {
        // Active text editor in Chrome / DeX / hardware keyboard
        assertTrue(
            FloatingMicPolicy.shouldShowForImeSession(
                inputStarted = true,
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT,
                packageName = "com.sec.android.app.sbrowser",
                ownPackageName = "com.voiceime",
            ),
        )
        // Blurring a web input resets EditorInfo.inputType to TYPE_NULL (0) with no soft IME
        assertFalse(
            FloatingMicPolicy.shouldShowForImeSession(
                inputStarted = true,
                inputType = InputType.TYPE_NULL,
                packageName = "com.sec.android.app.sbrowser",
                ownPackageName = "com.voiceime",
                hasConfirmedNodeOrImeWindow = false,
            ),
        )
        // TYPE_NULL canvas editor while soft IME window IS explicitly open
        assertTrue(
            FloatingMicPolicy.shouldShowForImeSession(
                inputStarted = true,
                inputType = InputType.TYPE_NULL,
                packageName = "com.google.android.apps.docs.editors.docs",
                ownPackageName = "com.voiceime",
                hasConfirmedNodeOrImeWindow = true,
            ),
        )
        // Full-screen notification shade suppresses idle background IME session
        assertFalse(
            FloatingMicPolicy.shouldShowForImeSession(
                inputStarted = true,
                inputType = InputType.TYPE_CLASS_TEXT,
                packageName = "com.samsung.android.app.notes",
                ownPackageName = "com.voiceime",
                systemModalBlocking = true,
            ),
        )
        // Own app is always excluded
        assertFalse(
            FloatingMicPolicy.shouldShowForImeSession(
                inputStarted = true,
                inputType = InputType.TYPE_CLASS_TEXT,
                packageName = "com.voiceime",
                ownPackageName = "com.voiceime",
            ),
        )
    }

    @Test
    fun `distinguishes samsung taskbar from fullscreen notification shade`() {
        // Persistent Samsung Galaxy Tab taskbar (2800 x 112 on a 2800 x 1752 screen)
        assertFalse(
            FloatingMicPolicy.isFullScreenSystemOverlay(
                isActive = true,
                isFocused = true,
                windowWidth = 2800,
                windowHeight = 112,
                screenWidth = 2800,
                screenHeight = 1752,
            ),
        )
        // Fullscreen notification shade / Quick Settings
        assertTrue(
            FloatingMicPolicy.isFullScreenSystemOverlay(
                isActive = true,
                isFocused = true,
                windowWidth = 2800,
                windowHeight = 1752,
                screenWidth = 2800,
                screenHeight = 1752,
            ),
        )
    }

    @Test
    fun `hides inside Voice IME itself`() {
        assertFalse(
            FloatingMicPolicy.shouldShowForEditor(
                isEditable = true,
                isFocused = true,
                isPassword = false,
                packageName = "com.voiceime",
                ownPackageName = "com.voiceime",
            ),
        )
    }

    @Test
    fun `busy recording or transcribing state keeps stop target visible`() {
        assertTrue(FloatingMicPolicy.shouldKeepVisibleWhileBusy(true))
        assertFalse(FloatingMicPolicy.shouldKeepVisibleWhileBusy(false))
    }

    @Test
    fun `ignores hint placeholder text when splicing dictated text`() {
        assertEquals("", FloatingMicPolicy.textForInsertion("Message", "Message"))
        assertEquals(
            "",
            FloatingMicPolicy.textForInsertion(
                text = "Search or type URL",
                hintText = null,
                showingHintText = true,
            ),
        )
        assertEquals("Hello", FloatingMicPolicy.textForInsertion("Hello", "Message"))
        assertNull(FloatingMicPolicy.textForInsertion(null, "Message"))
    }

    @Test
    fun `merges live final prefix and interim hypothesis without duplication`() {
        assertEquals("hello world", FloatingMicPolicy.mergeLivePreview("", "hello world"))
        assertEquals("hello world", FloatingMicPolicy.mergeLivePreview("hello", "hello world"))
        assertEquals("hello world", FloatingMicPolicy.mergeLivePreview("hello world", "world"))
        assertEquals(
            "hello world today",
            FloatingMicPolicy.mergeLivePreview("hello world", "world today"),
        )
        assertEquals(
            "first sentence second part",
            FloatingMicPolicy.mergeLivePreview("first sentence", "second part"),
        )
    }

    @Test
    fun `live preview tail rolls forward on word boundaries`() {
        assertEquals("short line", FloatingMicPolicy.livePreviewTail("short line", 40))
        assertEquals(
            "three four five",
            FloatingMicPolicy.livePreviewTail("one two three four five", 18),
        )
    }

    @Test
    fun `peeks off the nearest screen edge leaving only a visible handle`() {
        assertEquals(1060, FloatingMicPolicy.peekedX(x = 960, screenWidth = 1080, windowSize = 112, visiblePx = 20))
        assertEquals(-92, FloatingMicPolicy.peekedX(x = 12, screenWidth = 1080, windowSize = 112, visiblePx = 20))
    }

    @Test
    fun `peeks clear of edge insets`() {
        assertEquals(
            1028,
            FloatingMicPolicy.peekedX(x = 960, screenWidth = 1080, windowSize = 112, visiblePx = 20, insetPx = 32),
        )
        assertEquals(
            -60,
            FloatingMicPolicy.peekedX(x = 12, screenWidth = 1080, windowSize = 112, visiblePx = 20, insetPx = 32),
        )
    }

    @Test
    fun `docks and clamps within screen bounds and tablet safe area across rotation`() {
        assertEquals(952, FloatingMicPolicy.dockedX(x = 700, screenWidth = 1080, windowSize = 112, marginPx = 16))
        assertEquals(16, FloatingMicPolicy.dockedX(x = 100, screenWidth = 1080, windowSize = 112, marginPx = 16))
        assertEquals(16, FloatingMicPolicy.clampY(y = -50, screenHeight = 2400, windowSize = 112, marginPx = 16))
        assertEquals(2272, FloatingMicPolicy.clampY(y = 3000, screenHeight = 2400, windowSize = 112, marginPx = 16))

        // Galaxy Tab Landscape (2800 x 1752, status bar 48px, taskbar 112px) -> Portrait (1752 x 2800)
        val landscapeBottomY = FloatingMicPolicy.clampYInSafeArea(
            y = 1752,
            screenHeight = 1752,
            windowSize = 96,
            marginPx = 16,
            insetTopPx = 48,
            insetBottomPx = 112,
        )
        assertEquals(1528, landscapeBottomY)
        val fraction = FloatingMicPolicy.yToFraction(
            y = landscapeBottomY,
            screenHeight = 1752,
            windowSize = 96,
            marginPx = 16,
            insetTopPx = 48,
            insetBottomPx = 112,
        )
        assertEquals(1.0f, fraction, 0.001f)

        val portraitRightX = FloatingMicPolicy.dockedXInSafeArea(
            dockRight = true,
            screenWidth = 1752,
            windowSize = 96,
            marginPx = 16,
            insetLeftPx = 0,
            insetRightPx = 0,
        )
        val portraitBottomY = FloatingMicPolicy.yFromFraction(
            fraction = fraction,
            screenHeight = 2800,
            windowSize = 96,
            marginPx = 16,
            insetTopPx = 48,
            insetBottomPx = 112,
        )
        assertEquals(1640, portraitRightX)
        assertEquals(2576, portraitBottomY)
    }
}
