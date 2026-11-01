package com.voiceime

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.view.accessibility.AccessibilityEvent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

/**
 * Pins the install-time permission and service surface documented for Android
 * 16/17 and Play Accessibility review. Any new permission, overlay flag, or
 * broad accessibility event listener should fail here until reviewed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PermissionSurfaceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun manifest_requests_only_internet_and_record_audio() {
        val packageInfo = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS,
        )
        val requested = packageInfo.requestedPermissions
            ?.filterNot { it.startsWith("${context.packageName}.DYNAMIC_RECEIVER") }
            ?.toSet()
            .orEmpty()

        assertEquals(
            setOf(
                "android.permission.INTERNET",
                "android.permission.RECORD_AUDIO",
            ),
            requested,
        )
    }

    @Test
    fun services_require_platform_ime_and_accessibility_permissions() {
        val imeInfo = context.packageManager.getServiceInfo(
            ComponentName(context, VoiceImeService::class.java),
            0,
        )
        val floatingInfo = context.packageManager.getServiceInfo(
            ComponentName(context, FloatingMicAccessibilityService::class.java),
            0,
        )

        assertEquals("android.permission.BIND_INPUT_METHOD", imeInfo.permission)
        assertEquals("android.permission.BIND_ACCESSIBILITY_SERVICE", floatingInfo.permission)
    }

    @Test
    fun accessibility_config_stays_focus_scoped_and_tablet_ready() {
        val androidNs = "http://schemas.android.com/apk/res/android"
        var eventTypes = ""
        var flags = ""
        var canPerformGestures: String? = null
        var isAccessibilityTool: String? = null
        var notificationTimeout: String? = null

        context.resources.getXml(R.xml.accessibility_service_config).use { parser ->
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG &&
                    parser.name == "accessibility-service"
                ) {
                    eventTypes = parser.getAttributeValue(androidNs, "accessibilityEventTypes")
                        .orEmpty()
                    flags = parser.getAttributeValue(androidNs, "accessibilityFlags")
                        .orEmpty()
                    canPerformGestures = parser.getAttributeValue(androidNs, "canPerformGestures")
                    isAccessibilityTool = parser.getAttributeValue(androidNs, "isAccessibilityTool")
                    notificationTimeout = parser.getAttributeValue(androidNs, "notificationTimeout")
                    break
                }
                parser.next()
            }
        }

        val mask = eventTypes.toIntOrNull()
            ?: eventTypes.removePrefix("0x").toIntOrNull(16)
        if (mask != null) {
            assertEquals(0, mask and AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED)
            assertEquals(0, mask and AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
            assertNotEquals(0, mask and AccessibilityEvent.TYPE_VIEW_CLICKED)
            assertNotEquals(0, mask and AccessibilityEvent.TYPE_VIEW_LONG_CLICKED)
            assertNotEquals(0, mask and AccessibilityEvent.TYPE_VIEW_FOCUSED)
            assertNotEquals(0, mask and AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED)
            assertNotEquals(0, mask and AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
            assertNotEquals(0, mask and AccessibilityEvent.TYPE_WINDOWS_CHANGED)
        } else {
            assertFalse(eventTypes.contains("typeViewTextChanged"))
            assertFalse(eventTypes.contains("typeWindowContentChanged"))
            assertTrue(eventTypes.contains("typeViewClicked"))
            assertTrue(eventTypes.contains("typeViewLongClicked"))
            assertTrue(eventTypes.contains("typeViewFocused"))
            assertTrue(eventTypes.contains("typeViewTextSelectionChanged"))
            assertTrue(eventTypes.contains("typeWindowStateChanged"))
            assertTrue(eventTypes.contains("typeWindowsChanged"))
        }

        val flagMask = flags.toIntOrNull()
            ?: flags.removePrefix("0x").toIntOrNull(16)
        if (flagMask != null) {
            // 0x40 = flagRetrieveInteractiveWindows, 0x02 = flagIncludeNotImportantViews,
            // 0x8000 = flagInputMethodEditor.
            assertNotEquals(0, flagMask and 0x40)
            assertNotEquals(0, flagMask and 0x02)
            assertNotEquals(0, flagMask and 0x8000)
        } else {
            assertTrue(flags.contains("flagRetrieveInteractiveWindows"))
            assertTrue(flags.contains("flagIncludeNotImportantViews"))
            assertTrue(flags.contains("flagInputMethodEditor"))
        }

        assertEquals("true", isAccessibilityTool)
        assertEquals("0", notificationTimeout)
        assertFalse(canPerformGestures == "true")
    }
}
