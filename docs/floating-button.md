# Floating dictation button

This feature adds a system-wide mic button without replacing the user's
ordinary keyboard. The button appears when an editable, non-password field has
focus, stays above the current app, and writes the final transcript at the
current selection.

## Why this is an AccessibilityService

An `InputMethodService` only owns an input view while **Voice IME is the
selected keyboard**. A button that follows a cursor while another keyboard is
open needs two Android capabilities that an IME does not have:

1. focused-editor accessibility events, and
2. an accessibility overlay window (`TYPE_ACCESSIBILITY_OVERLAY`).

The user must explicitly enable **Voice IME floating button** under Android's
Accessibility settings. This is not a generic `SYSTEM_ALERT_WINDOW` overlay
and the app does not request that permission. The service is exported so the
system can bind to it and is guarded by `BIND_ACCESSIBILITY_SERVICE`, which
prevents ordinary apps from binding.

Before opening system settings, the app discloses that the service can inspect
the focused non-password field and its selection to preserve the cursor when
inserting a transcript. Existing editor text is never sent to the transcription
API or logged; password fields are rejected, and notification access is not
used.

## Runtime flow

```text
focused editable field
        │ AccessibilityService event
        ▼
FloatingMicAccessibilityService
        │ overlay lifecycle + drag/peek/tap
        ▼
AudioRecorder ── PCM chunks ──► LlmClient.LiveSession
        │                             │
        │                             ├─ interim transcript → feedback bubble
        │                             └─ final transcript
        ▼
AccessibilityNodeInfo ACTION_SET_TEXT at the saved selection
        │ fallback
        └─ clipboard + ACTION_PASTE
```

The floating host deliberately reuses the app's existing `SettingsStore`,
`AudioRecorder`, `LlmClient`, key rotation, WebSocket Live transport, and model
routing. It does not copy or depend on Ramblr's runtime, providers, settings,
or UI code.

## Model behavior

Fresh installs default to `gemini-3.5-transcribe-live`. The existing model
router recognizes the `-live` model name and sends 16 kHz PCM chunks through the
existing Gemini Live WebSocket transport. `SMART` mode remains controlled by
the existing Smart transcription setting. If a user selects a non-Live model,
the floating button falls back to the existing record-then-transcribe path;
there is still no second backend.

Google's Live API requires `responseModalities: ["TEXT"]`,
`inputAudioTranscription`, and raw `audio/pcm;rate=16000` chunks. The current
`LlmClient` already implements those details, including manual endpointing,
final/interim handling, and cancellation.

## Interaction contract

- **Tap idle button:** start dictation. The control uses a compact approximately
  44 dp visual circle inside a 48 dp touch target so it stays easy to tap
  without dominating the editor.
- **Tap recording button:** stop, wait for the server's completed turn, and insert.
- **Interim transcript:** stays in the floating feedback bubble only; it never
  modifies the focused editor. The bubble continuously replaces its hypothesis
  and keeps the newest tail visible when the display limit is reached.
- **Tap transcribing button:** cancel.
- **Long-press transcribing button:** cancel.
- **Drag:** move the button and snap it to the nearest horizontal edge; the
  position is persisted in app-private preferences.
- **Idle for four seconds:** slide toward the edge, leaving a 20 dp tappable
  sliver. The sliver restores to the full button after 700 ms plus the short
  slide animation, then the four-second idle timer starts again. A tap while
  peeked restores it without starting dictation; a tap on the restored button
  starts dictation.
- **Recording:** the button changes color and pulses. The pulse respects
  Android's animator-duration/reduced-motion setting.
- **Focus disappears while busy:** the button remains reachable so the user
  can stop or cancel safely.
- **Password field:** no button is shown and no target node is retained.

The service subscribes only to click, input-focus, text-selection, and
window-transition events. It does not subscribe to text/content-change events,
poll the accessibility tree, or log event text/content descriptions. For focus
resolution it uses `AccessibilityService.findFocus(FOCUS_INPUT)` across
interactive windows, with an active-window fallback. A short, bounded retry
and a refresh of the retained editor node avoid hiding the button during brief
keyboard/window hand-offs without leaving a stale target visible. This is
particularly useful when Samsung Keyboard or the handwriting surface takes
the active window while the editor remains input-focused.

### Samsung tablets and S Pen

On Galaxy tablets, **S Pen hover/Air view is a pointer preview, not input focus**.
Tap the text field with the S Pen to place the caret; hovering over it alone
should not open the mic. Samsung's **S Pen to text** handwriting mode can
change which IME window is active, so focus is resolved across windows rather
than only from the active window. The mic remains at its draggable, saved
screen position; editor focus controls whether it is shown, while dictation is
inserted at the editor's current selection. It is not attached to the S Pen
hover pointer. Samsung documents [S Pen hover and tablet input settings](https://www.samsung.com/ca/support/mobile-devices/galaxy-tablet-use-the-s-pen/)
and [Tab S10 FE S Pen to text](https://www.samsung.com/us/support/answer/ANS10005059/);
Android documents [input-focus lookup](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#findFocus(int)).

The overlay is hidden over the app's own settings screens; Voice IME's normal
keyboard remains available there.

## Android setup for testing

1. Open Voice IME settings and configure the existing backend credentials.
2. Set the model to `gemini-3.5-transcribe-live` (the default for new installs).
3. Tap **Enable floating button**.
4. Enable **Voice IME floating button** in Android Accessibility settings. If
   Android 13+ blocks the setting for a sideloaded APK, open **Settings → Apps
   → Voice IME → ⋮ → Allow restricted settings**, authenticate if prompted,
   return to Accessibility settings, and enable the service. OEM labels vary.
5. Open an app with a regular text field and tap it to place the caret; on a
   Galaxy tablet, S Pen hover alone does not create input focus. Wait briefly
   for the mic after the keyboard or handwriting surface opens. Notification
   access is not needed.
6. Grant microphone permission on the first tap, speak, and tap the button to
   finish.

Some custom editors do not expose `ACTION_SET_TEXT` or `ACTION_PASTE`. The
implementation reports a clipboard fallback instead of claiming insertion
succeeded. Real-device validation is required for OEM keyboards, Compose
editors, terminal surfaces, split-screen, and foldables. On a Galaxy Tab S10 FE,
validate finger tap, S Pen tap, S Pen-to-text handwriting, hover-only (no mic),
Samsung Keyboard/other IME changes, rotation, and split-screen. The sandbox has
no physical Samsung device, so OEM behavior still needs this device check.

## Research note

The interaction model was informed by the public
[trevornk/ramblr](https://github.com/trevornk/ramblr) project: its documented
floating icon uses an AccessibilityService overlay, drag-to-edge positioning,
idle peek/restore, state colors, and a recording pulse. This repository
implements the same product pattern independently and keeps its backend and
privacy boundary local to Voice IME. Ramblr is GPL-3.0; no Ramblr source file
is included here.
