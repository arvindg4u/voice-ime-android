# Floating Dictation Button — Design & Play-Policy Notes

Voice IME ships two complementary input surfaces in the same lightweight APK:

1. **`VoiceImeService`** (`InputMethodService`) — the standard Android keyboard.
2. **`FloatingMicAccessibilityService`** (`AccessibilityService`) — an optional,
   draggable system-wide floating microphone that appears only while a text
   cursor is active, letting users keep their preferred typing keyboard (Gboard,
   Samsung Keyboard / Honeyboard, Book Cover Keyboard, S Pen handwriting,
   SwiftKey, etc.) while dictating with Gemini.

---

## Why `TYPE_ACCESSIBILITY_OVERLAY` (Not `SYSTEM_ALERT_WINDOW`)

| Approach | Permission UX | Focus Awareness | Direct Text Insertion | Android 14–17 Restricted-Settings |
|---|---|---|---|---|
| `SYSTEM_ALERT_WINDOW` ("Display over other apps") | Extra special-access screen | Blind — cannot tell if a text box or password field is focused | Cannot insert text without switching keyboards or forcing clipboard paste | Flagged heavily on sideloaded APKs and blocked over sensitive dialogs |
| **`AccessibilityService` + `TYPE_ACCESSIBILITY_OVERLAY` + `FLAG_INPUT_METHOD_EDITOR`** | Single toggle in **Settings → Accessibility → Voice IME floating button** | Receives editor focus, interactive windows on all displays, and API 33+ `InputMethod` start/finish callbacks | Commits directly via API 33+ `AccessibilityInputConnection.commitText`, `ACTION_SET_TEXT`, or `ACTION_PASTE` | Supported assistive-tool path (`android:isAccessibilityTool="true"`) |

---

## Privacy & Google Play Accessibility Policy Boundary

Google Play requires every `AccessibilityService` to have a narrow, user-facing
assistive purpose and an in-app prominent disclosure before navigating to system
Accessibility settings.

Voice IME enforces these technical guardrails in code (`FloatingMicPolicy`,
`FloatingEditorResolver`, `FloatingMicAccessibilityService`, and
`accessibility_service_config.xml`):

- **Focus-only event subscription:** Listens to `typeViewClicked`,
  `typeViewLongClicked`, `typeViewFocused`, `typeViewTextSelectionChanged`,
  `typeWindowStateChanged`, and `typeWindowsChanged` (`notificationTimeout="0"`),
  plus Android 13+ `flagInputMethodEditor` (`InputMethod.onStartInput` /
  `onFinishInput` / `onUpdateSelection`). It deliberately **excludes**
  `typeWindowContentChanged` and `typeViewTextChanged`, so it does not receive a
  stream of screen text or keystrokes.
- **Assistive tool & custom-wrapper flags:** Declares
  `android:isAccessibilityTool="true"` (preventing Android 14–16
  `accessibilityDataSensitive` blanking and Android 17 non-tool revocation) and
  `flagIncludeNotImportantViews` (preventing `AccessibilityInteractionController`
  from aborting focus discovery inside custom/hybrid wrapper layouts).
- **Multi-layer focus resolution for Samsung Galaxy Tab (Android 16/17):**
  1. Direct `event.source` inspection (plus up to 4-hop ancestor climb for
     `contenteditable` `<p>`/`<span>` DOM children in Notion/Obsidian/WebView
     and bounded 250-node subtree scan for virtual Compose/WebView children).
  2. Global `findFocus(FOCUS_INPUT)`.
  3. Interactive windows across all displays (`windowsOnAllDisplays` on API 30+,
     ordered by `isFocused`, `isActive`, `layer`) so split-screen, freeform
     pop-up windows, Android 16/17 App Bubbles, and Samsung DeX external
     monitors are never shadowed by Samsung's Taskbar or floating Honeyboard.
  4. Android 13+ (`API 33+`) `AccessibilityService.InputMethod` session state
     (`EditorInfo`), catching Chromium/WebView before async native AXTree
     startup, canvas editors (Google Docs, spreadsheets, terminals), and
     physical Book Cover Keyboard sessions where soft keyboards stay hidden.
  5. Generation-guarded progressive discovery retries (`80, 220, 500, 1000,
     1800 ms`) with zero idle polling.
- **Strict password & own-app vetoes:** Checks `isPassword` **and** all four
  `InputType` password variations (`TYPE_TEXT_VARIATION_PASSWORD`,
  `VISIBLE_PASSWORD`, `WEB_PASSWORD`, `TYPE_NUMBER_VARIATION_PASSWORD`) across
  both `AccessibilityNodeInfo` and `EditorInfo`, and excludes `com.voiceime`
  itself.
- **No screen harvesting:** Existing field text is read only at the moment of
  `ACTION_SET_TEXT` fallback to splice dictated words at the current selection
  (ignoring `hintText` and `isShowingHintText`) and is never logged, stored, or
  transmitted.
- **Prominent disclosure & Samsung One UI diagnostics:** `SettingsActivity`
  explains why Accessibility access is used before opening system Accessibility
  settings, and warns if Samsung One UI Deep Sleep / battery optimization has
  stopped the background service binding.

---

## Gesture, Geometry & Visual State Machine

- **Idle:** Compact `48dp` dark-slate circle (`FloatingMicView`), docked to the
  nearest horizontal safe-area edge (`dockedXInSafeArea`) and stored as a
  normalized vertical fraction (`yToFraction` / `yFromFraction`) so tablet
  Landscape ↔ Portrait rotation, Samsung Taskbar, and display cutouts never
  strand the icon off-screen. Binds a per-display `WindowContext` so DeX
  external monitors show the button on the active display. After `4s` of
  inactivity it briefly peeks into a slim edge handle and restores `700ms` later.
- **Tap (Idle → Recording):** Expands with a coral-red core, white stop square,
  keep-screen-on protection during hands-free tablet dictation, and a real-time
  amplitude ring driven by `AudioRecorder` RMS levels. When a Gemini Live model
  is configured, a non-focusable speech bubble beside the mic displays rolling
  live words as you speak.
- **Tap (Recording → Transcribing):** Amber sweep spinner while awaiting Gemini
  Live finalization or HTTP batch fallback (`LlmClient`).
- **Delivery:** 4-tier insertion cascade:
  1. API 33+ `AccessibilityInputConnection.commitText(clean, 1, null)` (preserves
     clipboard and works natively in WebView, Compose, Word, Notes, DeX).
  2. Re-validated `AccessibilityNodeInfo` `ACTION_SET_TEXT` + cursor update.
  3. Clipboard + `node.refresh()` + `ACTION_PASTE`.
  4. API 33+ `TYPE_NULL` `commitText` / clipboard copy with paste toast.
- **Drag:** Smoothly repositions along screen edges; snaps to the nearest left
  or right safe-area edge on release and persists dock side + vertical fraction.
- **Long-press:** Cancels an active recording/transcription, or opens a quick
  menu to jump into Voice IME Settings when idle.
