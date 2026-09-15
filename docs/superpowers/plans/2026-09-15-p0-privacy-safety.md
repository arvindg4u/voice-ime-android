# P0 Privacy + Safety Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the six P0 privacy/safety findings from the full codebase review so history is encrypted, keys can't leak to attacker hosts, release builds stop logging transcripts, commits are bounded, and disabling history actually deletes it.

**Architecture:** Five small surgical fixes (pure-function-first, each with a unit test) plus one scoped crypto task: a `HistoryCrypto` seal/open seam in `ClipboardStore` backed by `EncryptedSharedPreferences`-grade key material (`MasterKey`, already a dependency via `androidx.security:security-crypto`), with legacy-plaintext fallback so existing files migrate on next write. No new Maven repositories; no DI framework; no emulator.

**Tech Stack:** Kotlin, Robolectric 4.12.1 (test), JUnit 4.13.2, `androidx.security:security-crypto:1.1.0-alpha06` (existing), AGP 8.5.2, JDK 17

**Spec:** Consolidated code-review report (session 2026-09-15): P0 list — encrypt history at rest (H3), enforce https + cleartext block (H4), Settings toggle deletes files (H2), gate release logging (M1), commit-text cap (M2). Cancellation trio (H1) is P1 and explicitly out of scope.

## Global Constraints

- minSdk 26, targetSdk/compileSdk 34.
- JDK 17; Gradle env exports required on this box (see `docs/testing-local-ci.md`): `JAVA_HOME`, `ANDROID_HOME`, `ANDROID_SDK_ROOT`, `PATH`, `GRADLE_USER_HOME` all under `/teamspace/studios/this_studio/`.
- `RepositoriesMode.FAIL_ON_PROJECT_REPOS` — google()/mavenCentral() only; new artifacts allowed, new repos forbidden.
- Existing tests keep passing unmodified (especially the 9 `ClipboardStoreTest` tests using `forDirectory(dir)` with no Context).
- New tests go in `src/test` (plain JUnit for pure logic; `@RunWith(RobolectricTestRunner::class)` + `@Config(sdk = [34])` only for framework behavior).
- Every task ends with `./gradlew :app:testDebugUnitTest` green and a commit.
- Commit messages: conventional commits + `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`.

---

### Task 1: Enforce https baseUrl + block cleartext

**Files:**
- Modify: `app/src/main/java/com/voiceime/SettingsStore.kt:82-91` (companionize + scheme check), `app/src/main/java/com/voiceime/SettingsActivity.kt:77-87` (toast on reset), `app/src/main/res/values/strings.xml` (new string), `AndroidManifest.xml` (application attrs)
- Create: `app/src/main/res/xml/network_security_config.xml`, `app/src/test/java/com/voiceime/SettingsStoreTest.kt`

**Interfaces:**
- Consumes: nothing (first task).
- Produces: `SettingsStore.normalize(raw: String): String` (companion, pure); `settings_https_required` string; `network_security_config.xml` referenced by manifest.

- [ ] **Step 1: Write the failing test** (`SettingsStoreTest.kt`, plain JUnit — no Robolectric; `normalize` is pure):

```kotlin
package com.voiceime

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsStoreTest {

    @Test
    fun `normalize keeps https url minus trailing slash`() {
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta",
            SettingsStore.normalize("https://generativelanguage.googleapis.com/v1beta/"),
        )
    }

    @Test
    fun `normalize rejects http to default`() {
        assertEquals(SettingsStore.DEFAULT_BASE_URL, SettingsStore.normalize("http://proxy:8080"))
    }

    @Test
    fun `normalize rejects non-url garbage to default`() {
        assertEquals(SettingsStore.DEFAULT_BASE_URL, SettingsStore.normalize("not a url"))
    }

    @Test
    fun `normalize keeps legacy suffix stripping`() {
        assertEquals(
            "https://example.com/v1",
            SettingsStore.normalize("https://example.com/v1/chat/completions"),
        )
    }

    @Test
    fun `normalize blank falls back to default`() {
        assertEquals(SettingsStore.DEFAULT_BASE_URL, SettingsStore.normalize("   "))
    }

    @Test
    fun `default base url is https`() {
        assertEquals(true, SettingsStore.DEFAULT_BASE_URL.startsWith("https://"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.voiceime.SettingsStoreTest" --stacktrace`
Expected: FAIL — `SettingsStore.normalize` does not exist (compilation error `unresolved reference`).

- [ ] **Step 3: Minimal implementation** — in `SettingsStore.kt`, move the body of `normalizeBaseUrl` into the companion as `normalize`, add the scheme gate, keep the instance method delegating:

```kotlin
companion object {
    // ... existing constants ...
    /** Fail-closed: anything that is not an https URL resets to default. */
    fun normalize(raw: String): String {
        var url = raw.trim()
        if (url.isEmpty()) return DEFAULT_BASE_URL
        url = url.trimEnd('/')
        if (url.endsWith("/chat/completions")) {
            url = url.removeSuffix("/chat/completions").trimEnd('/')
        }
        if (url.isEmpty()) return DEFAULT_BASE_URL
        val scheme = url.substringBefore("://", "").lowercase()
        if (scheme != "https") return DEFAULT_BASE_URL
        return url
    }
}
```

```kotlin
fun normalizeBaseUrl(raw: String): String = normalize(raw)
```

Getter/setter keep calling `normalizeBaseUrl(...)` unchanged (no caller churn).

- [ ] **Step 4: `save()` surfaces the reset** — in `SettingsActivity.save()`:

```kotlin
val typed = baseUrlField.text?.toString().orEmpty()
settings.baseUrl = typed
if (typed.isNotBlank() && settings.baseUrl == SettingsStore.DEFAULT_BASE_URL &&
    typed.trim() != SettingsStore.DEFAULT_BASE_URL
) {
    Toast.makeText(this, R.string.settings_https_required, Toast.LENGTH_LONG).show()
}
```

Add to `strings.xml`: `<string name="settings_https_required">Base URL must use https — reset to default</string>`

- [ ] **Step 5: Platform-level cleartext block** — create `app/src/main/res/xml/network_security_config.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <base-config cleartextTrafficPermitted="false" />
</network-security-config>
```

In `AndroidManifest.xml` `<application>` add: `android:networkSecurityConfig="@xml/network_security_config"` and `android:usesCleartextTraffic="false"`.

- [ ] **Step 6: Run tests**

Run: `./gradlew :app:testDebugUnitTest --stacktrace`
Expected: BUILD SUCCESSFUL, all suites green (106 + 6 new).

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/voiceime/SettingsStore.kt app/src/main/java/com/voiceime/SettingsActivity.kt app/src/main/res/values/strings.xml app/src/main/AndroidManifest.xml app/src/main/res/xml/network_security_config.xml app/src/test/java/com/voiceime/SettingsStoreTest.kt
git commit -m "fix: enforce https base URL and block cleartext traffic"
```

---

### Task 2: Settings clipboard toggle deletes history files on disable

**Files:**
- Modify: `app/src/main/java/com/voiceime/ClipboardHistoryActivity.kt:150-175` (add companion helper), `app/src/main/java/com/voiceime/SettingsActivity.kt:48-52,70-75` (route through helper on IO)
- Create: `app/src/test/java/com/voiceime/ClipboardToggleTest.kt`

**Interfaces:**
- Consumes: Task 1's strings/manifest (untouched by this task); `ClipboardStore.setEnabled` (exists, deletes files on disable).
- Produces: `ClipboardHistoryActivity.setClipboardEnabled(context, checked)` — the single helper both toggles route through.

- [ ] **Step 1: Write the failing test** (`ClipboardToggleTest.kt`, Robolectric — needs real `noBackupFilesDir`):

```kotlin
package com.voiceime

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ClipboardToggleTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private fun historyFile(): File = File(context.noBackupFilesDir, ClipboardStore.FILE_NAME)

    @Test
    fun `disabling via helper deletes history files`() {
        // Arrange
        ClipboardHistoryActivity.setClipboardEnabled(context, true)
        val store = ClipboardStore.forContext(context)
        store.setEnabled(true)
        store.add("secret", nowMs = 1_000L)
        assertTrue(historyFile().exists())

        // Act
        ClipboardHistoryActivity.setClipboardEnabled(context, false)

        // Assert
        assertFalse(historyFile().exists())
        assertFalse(File(context.noBackupFilesDir, "${ClipboardStore.FILE_NAME}.bak").exists())
        assertEquals(false, ClipboardHistoryActivity.isEnabled(context))
    }

    @Test
    fun `re-enable after disable resurfaces nothing`() {
        ClipboardHistoryActivity.setClipboardEnabled(context, true)
        val store = ClipboardStore.forContext(context)
        store.setEnabled(true)
        store.add("gone", nowMs = 1_000L)
        ClipboardHistoryActivity.setClipboardEnabled(context, false)
        ClipboardHistoryActivity.setClipboardEnabled(context, true)
        assertTrue(ClipboardStore.forContext(context).load().isEmpty())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.voiceime.ClipboardToggleTest" --stacktrace`
Expected: FAIL — `setClipboardEnabled` unresolved reference.

- [ ] **Step 3: Minimal implementation** — companion helper in `ClipboardHistoryActivity`:

```kotlin
/**
 * Single toggle both screens route through: flips the pref and applies
 * it to the store (disabling deletes history files). Synchronous file
 * IO — callers must be off the main thread.
 */
fun setClipboardEnabled(context: Context, checked: Boolean) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit().putBoolean(KEY_ENABLED, checked).apply()
    ClipboardStore.forContext(context).setEnabled(checked)
}
```

History screen: replace `setHistoryEnabled(checked)` body (`:153-155`) with a call to the helper (already inside an IO scope at `:75-79`). Settings screen: replace `setClipHistoryEnabled` body with an IO-dispatched helper call:

```kotlin
private fun setClipHistoryEnabled(checked: Boolean) {
    lifecycleScope.launch {
        withContext(Dispatchers.IO) {
            ClipboardHistoryActivity.setClipboardEnabled(this@SettingsActivity, checked)
        }
    }
}
```

(`lifecycleScope`, `Dispatchers`, `withContext` already imported in `SettingsActivity.kt`.)

- [ ] **Step 4: Run tests**

Run: `./gradlew :app:testDebugUnitTest --stacktrace`
Expected: BUILD SUCCESSFUL, all green.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/voiceime/ClipboardHistoryActivity.kt app/src/main/java/com/voiceime/SettingsActivity.kt app/src/test/java/com/voiceime/ClipboardToggleTest.kt
git commit -m "fix: route Settings clipboard toggle through store file deletion"
```

---

### Task 3: Gate release logging behind BuildConfig.DEBUG, strip payloads

**Files:**
- Modify: `app/src/main/java/com/voiceime/LlmClient.kt:78-82,111,120,124,256-264,483-485,503-506,514,518,548-552,581`
- Test: none new (Log is stubbed under `isReturnDefaultValues`; nothing pure to assert) — verification is full-suite regression + grep proof below.

**Interfaces:**
- Consumes: Task 1–2 (untouched files).
- Produces: DEBUG-gated `debugLog`; no `payload.take` / transcript `text.take` in log calls.

- [ ] **Step 1: Prove the current leak exists (RED-by-inspection)**

Run: `grep -n "payload.take(2000)\|text.take(600)" app/src/main/java/com/voiceime/LlmClient.kt`
Expected: three hits — `:111` (`payload=${payload.take(2000)}`), `:581` (same, interactions), `:262` (`text.take(600)`).

- [ ] **Step 2: Gate + strip**

```kotlin
/** Diagnostic log only: never pass apiKey or audio data here. Release builds stay silent. */
private fun debugLog(tag: String, msg: String) {
    if (BuildConfig.DEBUG) android.util.Log.d("VoiceIme", "$tag: $msg")
}
```

(`BuildConfig` is same-package `com.voiceime` — no import. Unit-test builds have `DEBUG=true`, so tests are unaffected.)

Replace `:111` with: `debugLog("transcribe", "HTTP $code key=$idx bytes=${payload.length}")`
Replace `:581` with: `debugLog("interactions", "HTTP $code key=$idx bytes=${payload.length}")`
Replace `:260-263` block with length/shape only:

```kotlin
if (shapeLogged < 6) {
    shapeLogged++
    log("live-rx", "msg#$shapeLogged len=" + text.length)
}
```

And fix the stale comment above it (`:257-259` "log them whole") to: `// Length/shape only: server replies carry transcripts, which never hit logcat.`

- [ ] **Step 3: Verify the leak is gone**

Run: `grep -n "payload.take(2000)\|text.take(600)" app/src/main/java/com/voiceime/LlmClient.kt`
Expected: no output (exit 1).

- [ ] **Step 4: Run tests**

Run: `./gradlew :app:testDebugUnitTest --stacktrace`
Expected: BUILD SUCCESSFUL, all green.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/voiceime/LlmClient.kt
git commit -m "fix: gate diagnostic logging to debug builds, strip transcripts"
```

---

### Task 4: Cap commit text at 32k with user-visible truncation

**Files:**
- Modify: `app/src/main/java/com/voiceime/ClipboardPolicy.kt:30-31` (const + pure fn), `app/src/main/java/com/voiceime/VoiceImeService.kt:479,511-519,776-806,1051-1078` (apply cap), `app/src/main/res/values/strings.xml` (new string)
- Test: extend `app/src/test/java/com/voiceime/ClipboardPolicyTest.kt` (existing file, append tests)

**Interfaces:**
- Consumes: Task 1's strings.xml addition (different key, append — no conflict); Task 2–3 (untouched files).
- Produces: `ClipboardPolicy.MAX_COMMIT_CHARS`, `ClipboardPolicy.truncateCommit(text)`; `ime_commit_truncated` string.

- [ ] **Step 1: Write the failing tests** (append to `ClipboardPolicyTest.kt`):

```kotlin
@Test
fun `truncateCommit passes through at and below cap`() {
    val exact = "x".repeat(ClipboardPolicy.MAX_COMMIT_CHARS)
    assertEquals(exact, ClipboardPolicy.truncateCommit(exact))
    assertEquals("hi", ClipboardPolicy.truncateCommit("hi"))
}

@Test
fun `truncateCommit truncates above cap`() {
    val big = "y".repeat(ClipboardPolicy.MAX_COMMIT_CHARS + 500)
    val out = ClipboardPolicy.truncateCommit(big)
    assertEquals(ClipboardPolicy.MAX_COMMIT_CHARS, out.length)
    assertEquals("y".repeat(ClipboardPolicy.MAX_COMMIT_CHARS), out)
}

@Test
fun `max commit chars is 32k`() {
    assertEquals(32 * 1024, ClipboardPolicy.MAX_COMMIT_CHARS)
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.voiceime.ClipboardPolicyTest" --stacktrace`
Expected: FAIL — `MAX_COMMIT_CHARS` / `truncateCommit` unresolved.

- [ ] **Step 3: Minimal implementation** — in `ClipboardPolicy`:

```kotlin
/** Commit-text cap for editors; overlong transcripts truncate with a user note. */
const val MAX_COMMIT_CHARS = 32 * 1024

fun truncateCommit(text: String): String =
    if (text.length > MAX_COMMIT_CHARS) text.take(MAX_COMMIT_CHARS) else text
```

- [ ] **Step 4: Apply at commit sites** — `deliverTranscript` (`VoiceImeService.kt:776`): after the blank check, `val capped = ClipboardPolicy.truncateCommit(text)` and use `capped` for `maybeSaveClip`, clipboard `setPrimaryClip`, fingerprint, and `commitText`. After commit, if truncated, notify:

```kotlin
if (capped.length != text.length) {
    Toast.makeText(this, R.string.ime_commit_truncated, Toast.LENGTH_LONG).show()
}
```

(`Toast` is already used in this file, e.g. `:826`.) `commitClipEntry` (`:1051`): cap before clipboard+commit (silent — store gate already bounds these to 8k). Live accumulator (`onFinal` `:511-519`): bound growth by dropping the oldest beyond cap right after `liveFinalParts.append(text)`:

```kotlin
if (liveFinalParts.length > ClipboardPolicy.MAX_COMMIT_CHARS) {
    liveFinalParts.delete(0, liveFinalParts.length - ClipboardPolicy.MAX_COMMIT_CHARS)
}
```

Add string: `<string name="ime_commit_truncated">Long transcript truncated to 32k characters</string>`

- [ ] **Step 5: Run tests**

Run: `./gradlew :app:testDebugUnitTest --stacktrace`
Expected: BUILD SUCCESSFUL, all green.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/voiceime/ClipboardPolicy.kt app/src/main/java/com/voiceime/VoiceImeService.kt app/src/main/res/values/strings.xml app/src/test/java/com/voiceime/ClipboardPolicyTest.kt
git commit -m "fix: cap commit text at 32k with truncation notice"
```

---

### Task 5: Encrypt clipboard history at rest

**Files:**
- Modify: `app/src/main/java/com/voiceime/ClipboardStore.kt:27-38,130-175` (crypto seam + encrypted persist/read)
- Create: `app/src/main/java/com/voiceime/HistoryCrypto.kt`, `app/src/test/java/com/voiceime/HistoryCryptoTest.kt`

**Interfaces:**
- Consumes: Tasks 1–4 (untouched files).
- Produces: `HistoryCrypto` interface; `ClipboardStore.forContext` uses encrypted impl; `forDirectory(dir, crypto)` seam (default NoOp keeps the 9 existing tests green unmodified).

**Known risk (implementer must resolve, review loop adjudicates):** `EncryptedFile` AAD/filename binding determines whether swap+rename survives. Verify from the `security-crypto-1.1.0-alpha06` AAR in the Gradle cache before choosing: if AAD is filename-independent, keep swap+rename with `EncryptedFile` on the swap file; else write `EncryptedFile` directly to primary (`.bak` still protects). Record the decision + evidence in the task report. Keystore must use the same `MasterKey.AES256_GCM` pattern as `SettingsStore.kt:26-34`. Robolectric must prove it: if `MasterKey` fails under shadows, say so in the report and implement the seam with a documented fallback — do NOT silently ship NoOp as the production path.

- [ ] **Step 1: Write the failing tests** (`HistoryCryptoTest.kt`, Robolectric):

```kotlin
package com.voiceime

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HistoryCryptoTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `persisted history is not plaintext on disk`() {
        // Arrange
        val store = ClipboardStore.forContext(context)
        store.setEnabled(true)

        // Act
        store.add("cipher-me-please", nowMs = 1_000L)
        val raw = File(context.noBackupFilesDir, ClipboardStore.FILE_NAME)
            .readText(Charsets.UTF_8)

        // Assert
        assertFalse(raw.contains("cipher-me-please"))
        assertEquals(listOf("cipher-me-please"), store.snapshot().map { it.text })
    }

    @Test
    fun `round-trip survives reload`() {
        val store = ClipboardStore.forContext(context)
        store.setEnabled(true)
        store.add("persist-me", nowMs = 1_000L)
        val loaded = ClipboardStore.forContext(context).load()
        assertEquals(listOf("persist-me"), loaded.map { it.text })
    }

    @Test
    fun `legacy plaintext file migrates on load`() {
        // Arrange: hand-write the OLD plaintext format
        val array = org.json.JSONArray()
        array.put(
            org.json.JSONObject()
                .put("id", "legacy-1")
                .put("text", "legacy-entry")
                .put("createdAtMs", 1_000L)
                .put("isPinned", false)
                .put("isSensitive", false)
                .put("mimeType", "text/plain"),
        )
        File(context.noBackupFilesDir, ClipboardStore.FILE_NAME)
            .writeText(array.toString(), Charsets.UTF_8)

        // Act
        val loaded = ClipboardStore.forContext(context).load()

        // Assert
        assertEquals(listOf("legacy-entry"), loaded.map { it.text })
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.voiceime.HistoryCryptoTest" --stacktrace`
Expected: FAIL — `persisted history is not plaintext` fails (file contains the text today); migration passes already (documents current behavior).

- [ ] **Step 3: Minimal implementation**

`HistoryCrypto.kt`:

```kotlin
package com.voiceime

/** Seal/open for history payload bytes. `open` throws on undecryptable input; callers fall back to legacy plaintext parse. */
interface HistoryCrypto {
    fun seal(plain: ByteArray): ByteArray
    fun open(sealed: ByteArray): ByteArray
}
```

Production impl with `EncryptedFile` + `MasterKey` (same pattern as `SettingsStore`); `ClipboardStore.forContext(context)` wires it; `forDirectory(dir, crypto: HistoryCrypto = NoOpHistoryCrypto)` keeps existing tests green. `persist()` seals before swap-write; `readFile()` tries `open` then legacy plaintext; on legacy success, next `persist()` rewrites encrypted (migration). `deleteFiles()` unchanged (deletes all three files regardless of encoding).

- [ ] **Step 4: Run tests**

Run: `./gradlew :app:testDebugUnitTest --stacktrace`
Expected: BUILD SUCCESSFUL, all green including the 3 new tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/voiceime/ClipboardStore.kt app/src/main/java/com/voiceime/HistoryCrypto.kt app/src/test/java/com/voiceime/HistoryCryptoTest.kt
git commit -m "fix: encrypt clipboard history at rest with legacy migration"
```

---

## Self-Review

**1. Spec coverage:** H3→Task 5, H4/https→Task 1, H2-toggle→Task 2, M1→Task 3, M2→Task 4. H1 cancellation trio is P1 — excluded on purpose. `usesCleartextTraffic` + config (H4 platform half) rides with Task 1.

**2. Placeholder scan:** every step has exact code/commands/expected output. Task 3 has no new unit test — stated explicitly with the reason (Log stubbed) and two verifiable commands instead. Task 5's EncryptedFile-vs-rename decision is delegated with both branches specified and evidence required — a bounded ruling, not a TODO.

**3. Type consistency:** `SettingsStore.normalize(raw: String): String` matches existing `normalizeBaseUrl` body; `truncateCommit(text: String): String`, `MAX_COMMIT_CHARS = 32*1024` match test asserts; `setClipboardEnabled(context: Context, checked: Boolean): Unit` matches both call sites; `HistoryCrypto.seal/open(ByteArray): ByteArray` matches persist/readFile usage. String keys (`settings_https_required`, `ime_commit_truncated`) are new and unique vs `strings.xml` as read. `forDirectory(dir, crypto = NoOp...)` keeps all 9 existing `forDirectory(dir)` calls compiling.

## Execution Handoff

**Plan complete and saved to `docs/superpowers/plans/2026-09-15-p0-privacy-safety.md`. Two execution options:**

**1. Subagent-Driven (recommended)** - dispatch a fresh subagent per task, review between tasks, fast iteration

**2. Inline Execution** - execute tasks in this session using executing-plans, batch execution with checkpoints

**Which approach?**
