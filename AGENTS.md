# AGENTS.md

Voice-only Android keyboard. Kotlin, single Gradle module `:app`, namespace
`com.voiceime`. `README.md` is the product overview; this file is what an agent
needs to change the code safely.

## Build & test

JDK **17** is required (Kotlin 1.9.24, AGP 8.5.2, Gradle 8.7 wrapper).

```bash
./gradlew :app:testDebugUnitTest --stacktrace                     # CI-parity test command
./gradlew :app:testDebugUnitTest --tests "com.voiceime.LlmClientTest" --stacktrace
./gradlew assembleDebug                                           # -> app/build/outputs/apk/debug/app-debug.apk
```

- If `testDebugUnitTest` passes on JDK 17, it passes on CI — CI runs the exact
  same task (`.github/workflows/android.yml`, `release.yml`). No lint/detekt/
  ktlint is configured; there is no `src/androidTest` and no emulator. Don't add
  CI steps that need a device.
- Test result XML: `app/build/test-results/testDebugUnitTest/TEST-*.xml`.
- `assembleRelease` reads `VOICEIME_KEYSTORE_FILE` / `VOICEIME_STORE_PASSWORD` /
  `VOICEIME_KEY_ALIAS` / `VOICEIME_KEY_PASSWORD`; without them it falls back to
  the debug keystore, so local release builds are not meaningful — use CI.

### Toolchain gotcha
`docs/testing-local-ci.md` documents a machine-specific toolchain
(`/teamspace/studios/this_studio/.android-toolchain/` + a Gradle proxy on
`127.0.0.1:18104`). That path may not exist on your box. On a host with only
JDK 25 and no Android SDK, Gradle 8.7 will not run at all — provision JDK 17 +
Android SDK `platforms;android-34` + `build-tools;34.0.0` first, and don't treat
an environment failure as a code failure.

## Testing conventions

- All tests live in `src/test` (JVM). Plain JUnit for pure logic; add
  `@RunWith(RobolectricTestRunner::class)` + `@Config(sdk = [34])` only when a
  test needs real `Context` / `SharedPreferences` / resources. Never
  `src/androidTest`.
- Prefer existing seams over mocks: `ClipboardStore.forDirectory(dir, crypto)`
  (Context-free, pass `NoOpHistoryCrypto`), `ApplicationProvider.getApplicationContext()`.
  Use MockK/Mockito only to isolate network or hardware; `MockWebServer` for
  `LlmClient`. `unitTests.isReturnDefaultValues` and `isIncludeAndroidResources`
  are already enabled.
- Baseline: ~179 `@Test` methods across 16 files (`LlmClientTest` is the
  largest, ~60). `WaveformView` is intentionally not unit-tested — its math
  lives in `ImeLayoutPolicy`.

## Architecture (not obvious from filenames)

- Two input surfaces share the same `AudioRecorder` / `SettingsStore` /
  `LlmClient`: `VoiceImeService` (the `InputMethodService` keyboard) and the
  optional `FloatingMicAccessibilityService` (draggable AccessibilityService
  overlay). Only the floating service needs Accessibility; it must reject
  password fields and its own package before retaining a target.
- Transport selection is a model-name heuristic in `GeminiTransports.kt`:
  `isLiveModel` (names containing `-live` / `live-preview`) → `BidiGenerateContent`
  WebSocket via `LiveSocket`; the Interactions allowlist → `/interactions`;
  otherwise REST `POST {base}/models/{model}:generateContent` with inline base64
  WAV. There is runtime fallback on transport mismatch — keep new models
  consistent with this routing.
- `HistoryCrypto` seals history via `EncryptedFile`; because EncryptedFile binds
  its auth tag to the file name it seals/opens through a fixed scratch filename.
  Its software-Tink fallback is gated on the AndroidKeyStore provider being
  absent (i.e. Robolectric only). `ClipboardStore.forContext` always wires the
  encrypted impl — never wire `NoOpHistoryCrypto` in production.
- `ClipboardStore` serializes on a process-wide lock and exposes sync + `*Async`
  variants. `LlmClient` rotates API keys round-robin (429 → next key; non-429
  fails fast). Both are shared by the keyboard and floating paths.

## Constraints

- `RepositoriesMode.FAIL_ON_PROJECT_REPOS`: dependencies must resolve from
  `google()` or `mavenCentral()` — no new repositories.
- Privacy invariants are hard requirements, not preferences: only `INTERNET` +
  `RECORD_AUDIO` are declared; audio is memory-only and nulled after each
  request; keys live only in `EncryptedSharedPreferences`; `allowBackup=false`;
  cleartext is blocked (`cleartextTrafficPermitted=false`). Do **not** add
  permissions, persist audio to disk, store keys unencrypted, or log keys /
  transcripts — diagnostics are gated behind `BuildConfig.DEBUG`.
- Release flow: a `v*` tag runs `release.yml`, which fails unless the tag equals
  `versionName` in `app/build.gradle.kts` (currently `1.6.3`, `versionCode` 16).
  Bump both there.
- Commit style: conventional commits (`feat:` / `fix:` / `test:` / `docs:` …).

## Stale sources

`README.md` lags the code (source/test counts, a "5-min" vs "10-min" auto-stop
contradiction, version `1.6.2`). Trust `app/build.gradle.kts` and the code: the
recording cap is `AudioRecorder.MAX_DURATION_MS = 600_000` (10 min).
`docs/superpowers/plans/*.md` are historical task plans (and `.superpowers/` is
gitignored), not current instructions.
