package com.voiceime

import android.Manifest
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Pins the permission contract for keyboard, floating Accessibility, and notifications. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PermissionSurfaceTest {

    @Test
    fun `floating service is system-bound while notification access is absent`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val flags = PackageManager.GET_PERMISSIONS or PackageManager.GET_SERVICES
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, flags)
        val permissions = packageInfo.requestedPermissions.orEmpty().toSet()
        val services = packageInfo.services.orEmpty()

        assertFalse(Manifest.permission.POST_NOTIFICATIONS in permissions)
        assertFalse(Manifest.permission.BIND_NOTIFICATION_LISTENER_SERVICE in permissions)
        assertFalse(services.any {
            it.permission == Manifest.permission.BIND_NOTIFICATION_LISTENER_SERVICE
        })
        assertTrue(services.any {
            it.name == VoiceImeService::class.java.name &&
                it.permission == Manifest.permission.BIND_INPUT_METHOD
        })
        assertTrue(services.any {
            it.name == FloatingMicAccessibilityService::class.java.name &&
                it.permission == Manifest.permission.BIND_ACCESSIBILITY_SERVICE &&
                it.exported
        })
    }
}
