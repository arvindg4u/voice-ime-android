# Android 16: notifications, Accessibility, and setup

Voice IME has two distinct input modes:

- **Keyboard mode** is an `InputMethodService`, enabled in Android's
  **Keyboard / On-screen keyboard / Input methods** settings. It does not need
  Accessibility or notification access.
- **Floating-mic mode** is optional. It uses a user-enabled
  `AccessibilityService` so a mic can follow the focused editor while another
  keyboard is selected. Android's settings toggle is required; the app cannot
  enable it programmatically.

## Notifications are not used

The app does not post notifications and does not read other apps' notifications.
It therefore does not declare `POST_NOTIFICATIONS` or a
`NotificationListenerService`. Android's notification-posting runtime
permission and notification-listener access are separate capabilities; neither
is required for keyboard or floating-mic mode.

## Why floating mode needs Accessibility

Android's IME framework only owns the keyboard view while Voice IME is selected.
The optional floating button needs Android to report the focused editor and to
host its accessibility overlay. The service is protected by
`BIND_ACCESSIBILITY_SERVICE` and is enabled only by the user in system settings.

The service uses focus, selection, and window-transition events to locate one
focused, editable, non-password field. To preserve the cursor and selected range
when inserting dictated text, it can inspect that field's current text and
selection. It does not subscribe to text-change events, log the editor text, or
send existing field contents to the configured transcription API. Password
fields are rejected before a target is retained. Only the audio the user
records is sent for transcription; transcript text is inserted at the cursor or
copied as a fallback. Notification contents are never accessed.

## “App was denied access” on Android 13+

Android may block sensitive settings for sideloaded APKs. If you installed
Voice IME from an APK and Android blocks the **floating-mic Accessibility
service**, first verify that you trust the app, then use Android's manual flow:

1. Open **Settings → Apps → Voice IME** (use **See all apps** if needed).
2. Tap **More / ⋮ → Allow restricted settings** and authenticate if asked.
3. Return to **Accessibility → Installed apps** (OEM labels vary) and enable
   **Voice IME floating button**.

This opt-in is only for the floating button. The standard keyboard continues to
work with the service disabled. Do not grant Notification access for Voice IME;
it is not used. Android requires the user to make this choice, and the app
cannot bypass the restricted-settings safeguard.

## Android 16 protection for the API key

Settings already uses `FLAG_SECURE`. On Android 14 and later, the API-key input
is also marked accessibility-data-sensitive and rejects obscured touches. This
helps prevent accessibility services not designated as assistive tools and
obscuring overlays from reading or manipulating the saved credential, while
preserving normal support for system accessibility tools.

## Platform references

- [Android Help: Learn about restricted settings](https://support.google.com/android/answer/12623953)
- [Android Developers: Create an input method](https://developer.android.com/develop/ui/views/touch-and-input/creating-input-method)
- [Android Developers: Create an accessibility service](https://developer.android.com/guide/topics/ui/accessibility/service)
- [Android Developers: Notification runtime permission](https://developer.android.com/develop/ui/compose/notifications/notification-permission)
- [Android Developers: `NotificationListenerService`](https://developer.android.com/reference/android/service/notification/NotificationListenerService)
- [Android Developers: Android 16 behavior changes](https://developer.android.com/about/versions/16/behavior-changes-16)
- [Android Developers: Protect sensitive views from accessibility data access](https://developer.android.com/blog/posts/enhancing-android-security-stop-malware-from-snooping-on-your-app-data)
- [Google Play policy: Permissions and APIs that access sensitive information](https://support.google.com/googleplay/android-developer/answer/16558241)
