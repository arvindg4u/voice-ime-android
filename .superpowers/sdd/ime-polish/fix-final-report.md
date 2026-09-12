# Fix wave: final-review findings (review-40523ea..5f611ff)

Branch: `sdd/task-1-collapse-rows`, HEAD at fix time `5f611ff`. New commit on top, no rebase/amend. No build attempted (no cached Gradle distro, no network); verification by careful in-tree reading only.

## Finding 1 — Task 1 clip-strip scope: NO CODE CHANGE (pre-conformance holds)

In-tree evidence that the clip strip is already GONE unless content is visible:

- `app/src/main/res/layout/ime_clipboard_strip.xml:10` — `imeClipStripScroll` defaults to `android:visibility="gone"`, so the strip starts collapsed on every inflation.
- `app/src/main/java/com/voiceime/VoiceImeService.kt:948-972` (`refreshClipStrip()`) — driven from `onStartInputView` (`:270`), post-commit (`:793`), and clip events; each path funnels to `renderClipStrip()`. Disabled-history path sets `scroll.visibility = View.GONE` (`:956`).
- `VoiceImeService.kt:974-1022` (`renderClipStrip()`) — `scroll.visibility = View.GONE` when password field (`:979`) or when the entry list is empty (`:987`); `scroll.visibility = View.VISIBLE` (`:1021`) only on the path that added at least one entry button. So the scroll container is GONE in every no-content case and VISIBLE only with content — the exact "GONE unless content visible" contract Task 1 requires.
- Task 1's own actions-row work (`renderState()`, `VoiceImeService.kt:881-884` via `ImeLayoutPolicy.actionsRowVisible`) was already in the diff; the clip strip needed no diff because `refreshClipStrip`/`renderClipStrip` pre-conformed. Ledger claim verified.

No policy helper, wiring, or test added — there is no new logic to unit-test, and touching the strip would risk the record→transcribe→commit pipeline and clipboard save rules.

## Finding 2 — Wrong "fire now" doc: DOC-ONLY FIX

In-tree evidence of the contradiction: `ImeLayoutPolicy.nextTimerDelayMs` (`app/src/main/java/com/voiceime/ImeLayoutPolicy.kt:72`) computes `1000L - (elapsedMs.coerceAtLeast(0L) % 1000L)` — negative input is clamped to 0, yielding `1000L`, a full interval, never an immediate fire. But the KDoc (`:70`, "Negative elapsed (clock moved) means the boundary is now") and the test comment (`app/src/test/java/com/voiceime/ImeLayoutPolicyTest.kt:83`, "fire now") both claimed immediate firing.

Changes (doc-only, zero behavior change):
- `ImeLayoutPolicy.kt:66-71` KDoc reworded: "Negative elapsed (clock moved) is clamped to zero, so it schedules a full 1000ms interval rather than firing immediately."
- `ImeLayoutPolicyTest.kt:83` comment reworded: "Negative elapsed (clock moved): clamped to a full interval." Assertion (`assertEquals(1000L, …(-50L))`) unchanged — it already encoded the true behavior.

## Finding 3 — onEnterTapped haptic before null-guard: ONE-LINE MOVE

In-tree evidence: `VoiceImeService.kt:361-366` (pre-fix) had `hapticTap()` at `:362` preceding `val ic = currentInputConnection ?: return` at `:366`, so a null connection still buzzed with no action taken.

Change: moved `hapticTap()` to after the early return (`VoiceImeService.kt:365-366` post-fix: `val ic = currentInputConnection ?: return` then `hapticTap()`). Tap-haptics behavior otherwise identical — every path that previously reached the connection still buzzes exactly once; only the no-op null path is now silent. No ID, listener, or layout touched.

## Global constraints confirmation

- No `?attr/` or theme refs added; no M3 styles; no XML/layout changes at all (edits are 2 comment lines + 1 moved Kotlin line). `ime_voice_view.xml`, `ime_clipboard_strip.xml`, and all included IME layouts untouched — framework widgets + system colors only, as before.
- View IDs and listeners unchanged.
- No new logic in the service: Finding 2 is comments, Finding 3 is a reorder of two existing statements, Finding 1 is no change.
- `ImeLayoutPolicy` pure-policy boundary respected — no new policy helper was needed.
- Record→transcribe→commit pipeline, LiveSocket framing, key rotation, permission flow, clipboard save rules: none touched (clip-strip paths read-only in this wave).
