package com.voiceime

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.text.InputType
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Verifies multi-source focus resolution across Jetpack Compose host containers,
 * WebView/contenteditable child hierarchies, custom ACTION_SET_TEXT /
 * ACTION_IME_ENTER views, password vetoes, and Android 13+ InputMethod sessions.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FloatingEditorResolverTest {

    private class StubAccessibilityService : AccessibilityService() {
        override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
        override fun onInterrupt() = Unit
        override fun onBind(intent: Intent?) = null
    }

    private val defaultSafeArea = FloatingEditorResolver.DisplaySafeArea(
        widthPx = 2800,
        heightPx = 1752,
        insetTopPx = 48,
        insetBottomPx = 112,
    )

    private fun createService(): AccessibilityService =
        Robolectric.buildService(StubAccessibilityService::class.java).create().get()

    @Test
    fun `resolves focused virtual compose editor inside non-editable host container`() {
        val service = createService()
        @Suppress("DEPRECATION")
        val composeHost = AccessibilityNodeInfo.obtain().apply {
            packageName = "com.compose.notes"
            className = "androidx.compose.ui.platform.AndroidComposeView"
            isEnabled = true
            isFocused = true
            isEditable = false
        }
        @Suppress("DEPRECATION")
        val virtualTextField = AccessibilityNodeInfo.obtain().apply {
            packageName = "com.compose.notes"
            className = "android.widget.EditText"
            isEnabled = true
            isFocused = true
            isEditable = false
            addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_TEXT)
        }
        shadowOf(composeHost).addChild(virtualTextField)

        val resolution = FloatingEditorResolver.resolve(
            service = service,
            eventSource = composeHost,
            eventConfirmsSelection = false,
            imeSnapshot = FloatingEditorResolver.ImeSessionSnapshot(),
            ownPackageName = "com.voiceime",
            fallbackSafeArea = defaultSafeArea,
        )

        assertTrue(resolution.shouldShow)
        assertNotNull(resolution.targetNode)
        FloatingEditorResolver.recycleQuietly(resolution.targetNode)
        FloatingEditorResolver.recycleQuietly(virtualTextField)
        FloatingEditorResolver.recycleQuietly(composeHost)
    }

    @Test
    fun `climbs from inner paragraph node to contenteditable ancestor`() {
        val service = createService()
        @Suppress("DEPRECATION")
        val contentEditableDiv = AccessibilityNodeInfo.obtain().apply {
            packageName = "notion.id"
            className = "android.widget.EditText"
            isEnabled = true
            isFocused = false
            isEditable = true
        }
        @Suppress("DEPRECATION")
        val paragraphSpan = AccessibilityNodeInfo.obtain().apply {
            packageName = "notion.id"
            className = "android.view.View"
            isEnabled = true
            isFocused = true
            isEditable = false
        }
        shadowOf(contentEditableDiv).addChild(paragraphSpan)

        val resolution = FloatingEditorResolver.resolve(
            service = service,
            eventSource = paragraphSpan,
            eventConfirmsSelection = true,
            imeSnapshot = FloatingEditorResolver.ImeSessionSnapshot(
                inputStarted = true,
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT,
                packageName = "notion.id",
            ),
            ownPackageName = "com.voiceime",
            fallbackSafeArea = defaultSafeArea,
        )

        assertTrue(resolution.shouldShow)
        assertNotNull(resolution.targetNode)
        assertTrue(resolution.targetNode!!.isEditable)
        FloatingEditorResolver.recycleQuietly(resolution.targetNode)
        FloatingEditorResolver.recycleQuietly(paragraphSpan)
        FloatingEditorResolver.recycleQuietly(contentEditableDiv)
    }

    @Test
    fun `vetoes visible password variation even when active ime session exists`() {
        val service = createService()
        val visiblePwType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        @Suppress("DEPRECATION")
        val pwNode = AccessibilityNodeInfo.obtain().apply {
            packageName = "com.android.chrome"
            className = "android.widget.EditText"
            isEnabled = true
            isFocused = true
            isEditable = true
            isPassword = false
            inputType = visiblePwType
        }

        val resolution = FloatingEditorResolver.resolve(
            service = service,
            eventSource = pwNode,
            eventConfirmsSelection = false,
            imeSnapshot = FloatingEditorResolver.ImeSessionSnapshot(
                inputStarted = true,
                inputType = InputType.TYPE_CLASS_TEXT,
                packageName = "com.android.chrome",
            ),
            ownPackageName = "com.voiceime",
            fallbackSafeArea = defaultSafeArea,
        )

        assertFalse(resolution.shouldShow)
        assertNull(resolution.targetNode)
        FloatingEditorResolver.recycleQuietly(pwNode)
    }

    @Test
    fun `shows via Android 13+ InputMethod session when WebView or DeX omits AccessibilityNodeInfo focus`() {
        val service = createService()
        val resolution = FloatingEditorResolver.resolve(
            service = service,
            eventSource = null,
            eventConfirmsSelection = false,
            imeSnapshot = FloatingEditorResolver.ImeSessionSnapshot(
                inputStarted = true,
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE,
                packageName = "com.sec.android.app.sbrowser",
            ),
            ownPackageName = "com.voiceime",
            fallbackSafeArea = defaultSafeArea,
        )

        assertTrue(resolution.shouldShow)
        assertNull(resolution.targetNode)
        assertTrue(resolution.backedByImeSession)
        assertEquals(Display.DEFAULT_DISPLAY, resolution.displayId)
    }

    @Test
    fun `hides when WebView blurs editor and restarts IME with TYPE_NULL`() {
        val service = createService()
        val resolution = FloatingEditorResolver.resolve(
            service = service,
            eventSource = null,
            eventConfirmsSelection = false,
            imeSnapshot = FloatingEditorResolver.ImeSessionSnapshot(
                inputStarted = true,
                inputType = InputType.TYPE_NULL,
                packageName = "com.sec.android.app.sbrowser",
            ),
            ownPackageName = "com.voiceime",
            fallbackSafeArea = defaultSafeArea,
        )

        assertFalse(resolution.shouldShow)
        assertNull(resolution.targetNode)
    }
}
