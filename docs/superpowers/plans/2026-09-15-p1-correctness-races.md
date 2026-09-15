# P1 Correctness + Races Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the six P1 correctness findings from the full codebase review — swallowed coroutine cancellation, non-atomic store updates, the recorder join race, blocking/suspend-mismatched networking, and oversized/nested functions — so Cancel actually cancels, concurrent history writes converge, and no IO thread is ever blocked for 75 s.

**Architecture:** Two behavior fixes with teeth (cancellation rethrows; suspend conversion of the transcribe path that also cures the runBlocking block, the unstructured grace scope, the per-tap Thread, and the swallowed client-side cancellation), one concurrency fix (process-wide store lock + stress tests), one hardware-race fix (synchronized recorder buffer + lifecycle tests), and one structure task (function splits with zero behavior change). Tasks are ordered so signatures settle before bodies are split: T1 → T2 → T3 → T4 → T5. No new Maven repositories; no DI framework; no emulator.

**Tech Stack:** Kotlin, kotlinx-coroutines (existing `kotlinx-coroutines-android:1.8.1`), Robolectric 4.12.1 (test), JUnit 4.13.2, MockWebServer 4.12.0 (existing test dep), AGP 8.5.2, JDK 17

**Spec:** Consolidated code-review report (session 2026-09-15): P1 list — cancellation trio (H1), store atomicity (H5), recorder join race (H6) + onLevel guard (M6), runBlocking/unstructured client (H7) + rotation dedupe (M11) + accumulator contract (M7), oversized functions (H8) + nesting (H9). LOWs explicitly out of scope.

## Global Constraints

- minSdk 26, targetSdk/compileSdk 34.
- JDK 17; Gradle env exports required on this box (see `docs/testing-local-ci.md`): `JAVA_HOME`, `ANDROID_HOME`, `ANDROID_SDK_ROOT`, `PATH`, `GRADLE_USER_HOME` all under `/teamspace/studios/this_studio/`.
- `RepositoriesMode.FAIL_ON_PROJECT_REPOS` — google()/mavenCentral() only; new artifacts allowed, new repos forbidden. (`kotlinx-coroutines-test` is allowed if needed, but no task below requires it — `runBlocking` comes from the already-present coroutines core.)
- Existing tests keep passing unmodified unless a task explicitly says to update them (only T4 touches `LlmClientTest` call sites, mechanically wrapping in `runBlocking`).
- New tests go in `src/test` (plain JUnit for pure logic; `@RunWith(RobolectricTestRunner::class)` + `@Config(sdk = [34])` only for framework behavior).
- Every task ends with `./gradlew :app:testDebugUnitTest` green and a commit.
- Commit messages: conventional commits + `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`.
- Suite baseline: 123 tests, 0 failures (post-P0).

---

### Task 1: Rethrow CancellationException in service + history loaders

**Files:**
- Modify: `app/src/main/java/com/voiceime/VoiceImeService.kt` (7 catch sites, additive one-liners only)
- Test: none new (catches sit in framework-coupled coroutines; nothing pure to assert) — verification is the grep proofs below + full suite + review.

**Interfaces:**
- Consumes: nothing (first task).
- Produces: every coroutine-body `catch (e: Exception)` in the service is preceded by a cancellation rethrow. T5's splits must preserve these lines (carried explicitly in T5).

- [ ] **Step 1: Prove the defect exists (RED-by-inspection)**

Run: `grep -n "catch (e: Exception)" app/src/main/java/com/voiceime/VoiceImeService.kt`
Expected: hits including the `rec?.stop()` fallback (returns `0f`), the session finish / awaitFinal fall-throughs, the one-shot `rec?.stop()` fallback, and the clip-history background loads. `CancellationException` extends `Exception`, so every one of these converts cancellation into proceed-as-normal: cancelling `liveCommitJob` via Cancel (`cancelWork()`) falls through to `deliverTranscript`.

- [ ] **Step 2: Minimal implementation** — at each coroutine-body catch site, insert before the generic catch:

```kotlin
} catch (e: CancellationException) { throw e } catch (e: Exception) {
```

(`kotlinx.coroutines.CancellationException` — add the import; `Job`/`launch`/`withContext` imports already exist, follow their style.) No other change at any site: fallback values, comments, and control flow stay identical for non-cancellation failures. Catches in non-coroutine callbacks (socket listeners, Toast/commitText guards) are out of scope — record which sites were skipped and why in the report.

- [ ] **Step 3: Verify the fix**

Run: `grep -n -B1 "catch (e: Exception)" app/src/main/java/com/voiceime/VoiceImeService.kt | grep -c "CancellationException"`
Expected: `7` — every generic catch in coroutine bodies now has the rethrow directly above it. (If the count differs, inspect and record the reasoning in the report.)

- [ ] **Step 4: Run tests**

Run: `./gradlew :app:testDebugUnitTest --stacktrace`
Expected: BUILD SUCCESSFUL, 123/123 green.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/voiceime/VoiceImeService.kt
git commit -m "fix: rethrow CancellationException in service coroutine catches"
```

---

### Task 2: Atomic store updates under a process-wide lock + stress tests

**Files:**
- Modify: `app/src/main/java/com/voiceime/ClipboardStore.kt` (lock around compound ops)
- Test: extend `app/src/test/java/com/voiceime/ClipboardStoreTest.kt` (append; never edit the 9 existing tests)

**Interfaces:**
- Consumes: T1 (untouched files).
- Produces: companion-level lock + atomic compound ops. T5 does not touch this file — no downstream dependency.

- [ ] **Step 1: Write the failing tests** (append to `ClipboardStoreTest.kt`; plain JUnit, `forDirectory` + `NoOp` crypto default — no Robolectric needed):

```kotlin
@Test
fun `concurrent adds never lose entries`() {
    val dir = temp.newFolder()
    val s = ClipboardStore.forDirectory(dir).apply {
        enabled = true
        ttlMs = 0L
        maxUnpinned = 500
    }
    val threads = (0 until 4).map { t ->
        Thread {
            for (i in 0 until 50) s.add("t$t-i$i", nowMs = 1_000L + t * 100 + i)
        }
    }
    threads.forEach { it.start() }
    threads.forEach { it.join(30_000) }
    val texts = s.snapshot().map { it.text }.toSet()
    for (t in 0 until 4) for (i in 0 until 50) {
        assertTrue("missing t$t-i$i", texts.contains("t$t-i$i"))
    }
}

@Test
fun `snapshot never observes the empty middle state`() {
    val s = store().apply { maxUnpinned = 500 }
    s.add("seed", nowMs = 1_000L)
    val empties = java.util.concurrent.atomic.AtomicInteger(0)
    val stop = java.util.concurrent.atomic.AtomicBoolean(false)
    val writer = Thread {
        var n = 0
        while (!stop.get()) { s.add("w${n++}", nowMs = 2_000L + n); if (n > 200) n = 0 }
    }
    val readers = (0 until 3).map {
        Thread {
            while (!stop.get()) { if (s.snapshot().isEmpty()) empties.incrementAndGet() }
        }
    }
    writer.start(); readers.forEach { it.start() }
    Thread.sleep(2_000)
    stop.set(true); writer.join(30_000); readers.forEach { it.join(30_000) }
    assertEquals(0, empties.get())
}
```

(`store()` helper exists in the test file: `forDirectory(temp.newFolder())` with `enabled = true, ttlMs = 0L`.)

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.voiceime.ClipboardStoreTest" --stacktrace`
Expected: FAIL — the empty-middle test observes empties (proves the `clear()`+`addAll()` race); the adds test may pass or flake (documents today's luck, locks in tomorrow's guarantee).

- [ ] **Step 3: Minimal implementation** — companion-level lock (covers the two-instances-one-directory case: the activity and the service each own an instance over the same directory):

```kotlin
companion object {
    // ... existing ...
    /**
     * Process-wide guard for compound in-memory replace + file read-modify-write.
     * Per-instance locking is insufficient: the activity and the service each own
     * an instance over the same directory, so last-writer-wins could resurrect a
     * deleted entry or drop the other writer's entry.
     */
    private val STORE_LOCK = Any()
}
```

Wrap the bodies of `load`, `add`, `togglePin`, `deleteAt`, `clearUnpinned`, `clearAll`, `setEnabled`, and `snapshot` in `synchronized(STORE_LOCK) { ... }`. Keep every other line identical (prune logic, persist calls, return values).

- [ ] **Step 4: Run tests**

Run: `./gradlew :app:testDebugUnitTest --stacktrace`
Expected: BUILD SUCCESSFUL, all green including the 2 new tests (125 total).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/voiceime/ClipboardStore.kt app/src/test/java/com/voiceime/ClipboardStoreTest.kt
git commit -m "fix: atomic clipboard store updates under process-wide lock"
```

---

### Task 3: Recorder join race + onLevel guard + start() split

**Files:**
- Modify: `app/src/main/java/com/voiceime/AudioRecorder.kt` (buffer guard, `isAlive` check, `onLevel` wrap, `createRecord`/`captureLoop` extraction)
- Test: extend `app/src/test/java/com/voiceime/AudioRecorderTest.kt` (append lifecycle-edge tests; Robolectric start/stop only if the shadow supports it — see known risk)

**Interfaces:**
- Consumes: T1–T2 (untouched files).
- Produces: synchronized buffer, guarded `onLevel`, `start()` ≤ 50 lines via `createRecord()` + `captureLoop()`. T5 does not touch this file.

**Known risk (implementer resolves, review adjudicates):** `start()` needs `AudioRecord` hardware; plain JVM stubs throw. Robolectric ships `ShadowAudioRecord` — try a `start()`/`stop()` smoke test under `@RunWith(RobolectricTestRunner::class)` + `@Config(sdk = [34])`. If the shadow cannot drive the capture loop, say so with evidence in the report and cover the JVM-testable edges instead (below) — do NOT contort production code for testability.

- [ ] **Step 1: Write the tests** (append to `AudioRecorderTest.kt`):

```kotlin
@Test
fun `stop without start returns empty wav and silent peak`() {
    val rec = AudioRecorder()
    val (wav, peak) = rec.stop()
    assertEquals(44, wav.size)
    assertTrue(AudioRecorder.isSilent(peak))
}

@Test
fun `double stop is safe`() {
    val rec = AudioRecorder()
    rec.stop()
    val (wav, _) = rec.stop()
    assertEquals(44, wav.size)
}

@Test
fun `discard without start is safe`() {
    AudioRecorder().discard()
}
```

(Plus the Robolectric start/stop smoke test if the shadow cooperates — real capture-thread + `stop()` + WAV validity + repeated iterations to shake the join race.)

- [ ] **Step 2: Run tests to verify current behavior**

Run: `./gradlew :app:testDebugUnitTest --tests "com.voiceime.AudioRecorderTest" --stacktrace`
Expected: lifecycle-edge tests PASS already (documents current safe behavior — the race needs a blocked `record.read`, which JVM tests cannot produce); the Robolectric smoke (if written) is the genuine RED/GREEN carrier.

- [ ] **Step 3: Minimal implementation**

```kotlin
// buffer field:
private var buffer: ByteArrayOutputStream? = null
private val bufferLock = Any()
```

Capture loop writes under lock: `synchronized(bufferLock) { buffer?.write(chunk, 0, read) }`. `stop()`/`discard()` after the bounded join: `if (recordingThread?.isAlive == true) recordingThread?.join()` (unbounded — callers are already documented off-UI-thread), then snapshot under lock: `synchronized(bufferLock) { buffer?.toByteArray() ... }`. `onLevel` wrapped exactly like its neighbor:

```kotlin
try {
    onLevel(magnitude(lvl))
} catch (e: Exception) {
    // Listener failed; capture continues regardless.
}
```

Split `start()` into `createRecord(): AudioRecord` (mic setup through `recorder = record`, throws `IllegalStateException` as today) and `captureLoop(record: AudioRecord)` (thread body verbatim, with the two fixes above inside). `start()` becomes: guard, `createRecord()`, state setup, thread launch calling `captureLoop`. No behavior change besides the three fixes.

Magic numbers named per review: `BLOCKED_GRACE_MS = 2_000L` (mic-blocked grace), `CHUNK_BYTES = 3_200`, `NOISE_FLOOR = 0.0001f`, `STOP_JOIN_MS = 2_000L` (bounded joins).

- [ ] **Step 4: Run tests**

Run: `./gradlew :app:testDebugUnitTest --stacktrace`
Expected: BUILD SUCCESSFUL, all green.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/voiceime/AudioRecorder.kt app/src/test/java/com/voiceime/AudioRecorderTest.kt
git commit -m "fix: close recorder join race, guard level callback"
```

---

### Task 4: Suspend transcribe path (kills runBlocking, scope, Thread, client cancellation bug)

**Files:**
- Modify: `app/src/main/java/com/voiceime/LlmClient.kt` (`transcribe`, `liveTranscribe`, `interactionsTranscribe`, `liveAttempt`, key-rotation loops, grace watcher, connect thread, stale KDoc, `FinalAccumulator` caller doc), `app/src/main/java/com/voiceime/VoiceImeService.kt` (`transcribeJob` call sites only — remove `withContext` wrappers), `app/src/main/java/com/voiceime/SettingsActivity.kt` (`test()` only — remove `withContext` wrapper)
- Test: update `app/src/test/java/com/voiceime/LlmClientTest.kt` call sites mechanically (`runBlocking` wrap); add MockWebServer cancellation test (below)

**Interfaces:**
- Consumes: T1–T3 (untouched files). NOTE: T1 does not touch `SettingsActivity.test()` or `LlmClient` — this task owns both, including the remaining cancellation behavior (the suspend conversion subsumes it: cancellation now propagates instead of being caught).
- Produces: `suspend fun transcribe/...`; no `runBlocking`, no throwaway `CoroutineScope`, no raw connect `Thread`; extracted rotation helper; all three big functions ≤ 50 lines. T5 must not touch `LlmClient.kt` or the edited call sites.

- [ ] **Step 1: Write the failing test** (append to `LlmClientTest.kt`; follow the file's existing MockWebServer pattern):

```kotlin
@Test
fun `cancelling transcribe throws CancellationException, not TranscribeException`() = runBlocking {
    val server = MockWebServer()
    server.enqueue(MockResponse().setBodyDelay(10, TimeUnit.SECONDS).setBody("{}"))
    server.start()
    val url = server.url("/v1beta").toString().removeSuffix("/")
    val job = async(Dispatchers.IO) {
        client.transcribe(wavBytes(), listOf("k"), url, "gemini-2.5-flash")
    }
    delay(500)
    job.cancel()
    try {
        job.await()
        fail("expected CancellationException")
    } catch (e: CancellationException) {
        // Correct: structured cancellation propagates.
    } catch (e: TranscribeException) {
        fail("cancellation was converted to TranscribeException: ${e.message}")
    } finally {
        server.shutdown()
    }
}
```

(`runBlocking`, `async`, `Dispatchers`, `delay`, `CancellationException` imports as needed; `wavBytes()` helper exists in the file.)

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.voiceime.LlmClientTest" --stacktrace`
Expected: FAIL — `transcribe` is not `suspend` (compilation error on the `async` call), which is exactly the conversion this task performs.

- [ ] **Step 3: Minimal implementation**

  - `fun transcribe`, `liveTranscribe`, `interactionsTranscribe`, `liveAttempt` → `suspend fun`. Delete the stale `finish()` KDoc block (the one contradicting "Does NOT close").
  - Blocking `call.execute()`: wrap the execute+read in `withContext(Dispatchers.IO)` INSIDE the client (client enforces its own dispatcher now).
  - `liveAttempt`: wrap body in `coroutineScope { }`; grace watcher becomes a child `launch { ... }` (delete the throwaway `CoroutineScope(Dispatchers.IO).launch`); replace `runBlocking(Dispatchers.IO) { withTimeout(...) }` with plain `withTimeout(LIVE_TIMEOUT_MS) { deferred.await() }` (structured — child auto-cancelled); connect Thread becomes `withContext(Dispatchers.IO) { raw.connect() }`.
  - Client cancellation conversion: `catch (e: CancellationException) { throw TranscribeException("cancelled", e) }` becomes close-resources-then-rethrow: `catch (e: CancellationException) { try { raw.close(1000, "cancel") } catch (_: Exception) {} ; throw e }` — resources closed, cancellation propagates.
  - Extract the shared rotation+execute shape both loops duplicate (start-index rotation, `call.execute()`, 429-rotate, `inFlight` bookkeeping) into `executeWithRotation(...)`; keep each transport's specific branches (REST fallback switches; interactions fail-fast) at its call site. If the two loops resist a clean shared shape, extract only the identical start-index + 429-rotate fragment and say so in the report — do not force it.
  - Document `FinalAccumulator` — add `// Not thread-safe; call only from the socket-callback thread` plus a one-line O(n2) note; no behavior change.
  - Splits (each resulting function ≤ 50 lines): `transcribe` → `executeRestCall()` + `rotateOn429()`; `liveAttempt` → `sendLiveAudio()`; `interactionsTranscribe` → share `executeRestCall()` where identical.
  - Call sites: `VoiceImeService` `transcribeJob` launch and `retryLast` (same `withContext(Dispatchers.IO) { llm.transcribe(...) }` pattern) become direct `llm.transcribe(...)` calls (already inside `serviceScope.launch`); `SettingsActivity.test()` likewise. Nothing else at those sites changes.
  - Existing `LlmClientTest` transcribe call sites: wrap in `runBlocking` (mechanical, no assertion changes).

- [ ] **Step 4: Run tests**

Run: `./gradlew :app:testDebugUnitTest --stacktrace`
Expected: BUILD SUCCESSFUL, all green including the new cancellation test.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/voiceime/LlmClient.kt app/src/main/java/com/voiceime/VoiceImeService.kt app/src/main/java/com/voiceime/SettingsActivity.kt app/src/main/java/com/voiceime/FinalAccumulator.kt app/src/test/java/com/voiceime/LlmClientTest.kt
git commit -m "fix: suspend transcribe path, structured live concurrency"
```

---

### Task 5: Split oversized service/socket functions (zero behavior change)

**Files:**
- Modify: `app/src/main/java/com/voiceime/VoiceImeService.kt` (finishLiveStreaming, startLiveStreaming, renderState, showClipPopup, RECORD_CAP_MS, deleteClipEntry), `app/src/main/java/com/voiceime/LiveSocket.kt` (connect, readFrame)
- Test: none new (no behavior change by construction) — verification is full-suite green + review.

**Interfaces:**
- Consumes: T1 (rethrow lines now live inside `finishLiveStreaming` regions — PRESERVE them verbatim in extracted helpers); T4 (T4 never edits `VoiceImeService` split regions or `LiveSocket` — confirm by diff before starting; if T4 touched them, reconcile line numbers by function name).
- Produces: every function ≤ 50 lines, nesting ≤ 4, no duplicated 5-min cap. Final P1 task — nothing downstream.

- [ ] **Step 1: Record the pre-split baseline (RED-by-inspection)**

Locate by function name (line numbers may have shifted from T1/T4 insertions): `finishLiveStreaming` (~86 lines), `startLiveStreaming` (~77), `renderState` (~71), `showClipPopup` (~63), `LiveSocket.connect` (~65), `LiveSocket.readFrame` (~54). Record actual spans in the report.

- [ ] **Step 2: Minimal implementation** (extract-only; zero logic edits — move code, do not rewrite it):

  - `finishLiveStreaming` → `stopLiveRecorder()` (timer stop + recorder stop + peak), `awaitLiveFinal(session)` (finish + `awaitFinal` + close), `resolveLiveText()` (fallback-text assembly). T1's rethrow lines ride along inside the moved blocks — verify each survives with the Step-3 grep.
  - `startLiveStreaming` + `startRecording`: extract shared `beginCapture(rec)` for the timer/cap/state-render block both duplicate.
  - `renderState` → `renderTransportPill()`, `renderActionsRow()`, `renderEnterKey()`.
  - `showClipPopup` → extract the `BaseAdapter` to a named private function; move the long-click body (six nesting levels) to `private fun deleteClipEntry(entry: ClipboardEntry)` (fixes the nesting finding).
  - `RECORD_CAP_MS = 300_000L` private const; use at both `postDelayed` sites (locate by `postDelayed(cap`).
  - `LiveSocket.connect` → `openTlsSocket()`, `sendHandshake(output)`, `awaitHandshake(input)`.
  - `LiveSocket.readFrame` → `readFrameHeader()`, `handleControlFrame()`.

- [ ] **Step 3: Verify preservation + sizes**

Run: `grep -n -B1 "catch (e: Exception)" app/src/main/java/com/voiceime/VoiceImeService.kt | grep -c "CancellationException"`
Expected: same count as T1's Step-3 result — no rethrow lost in extraction.
Then: no `private fun` in either file spans > 50 lines (spot-check the extracted helpers; reviewer verifies exhaustively).

- [ ] **Step 4: Run tests**

Run: `./gradlew :app:testDebugUnitTest --stacktrace`
Expected: BUILD SUCCESSFUL, all green (pure moves — any red means an extraction changed behavior; stop and diff).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/voiceime/VoiceImeService.kt app/src/main/java/com/voiceime/LiveSocket.kt
git commit -m "refactor: split oversized service and socket functions"
```

---

## Self-Review

**1. Spec coverage:** service cancellation → T1, client cancellation → T4 (subsumed by suspend conversion, stated explicitly); store atomicity → T2; recorder race → T3, level-callback guard → T3; runBlocking/scope/Thread → T4, rotation dedupe → T4; accumulator contract → T4 (doc-only); oversized service/socket → T5, recorder/client splits ride with T3/T4; nesting → T5. Background-load catches ride in T1. No P1 item lacks a task; LOWs and P0 items excluded on purpose.

**2. Placeholder scan:** every step has exact code/commands/expected output. Three bounded delegations, each with both branches specified and evidence required (T3 ShadowAudioRecord, T4 rotation-helper shape, T1 non-coroutine-catch count mismatch). T1/T5 are grep-proof like P0-T3 precedent (framework-coupled code, nothing pure to assert) — stated explicitly with reasons.

**3. Type consistency:** `STORE_LOCK` companion `Any` + `synchronized` blocks match ClipboardStore shape; `createRecord(): AudioRecord` / `captureLoop(record: AudioRecord): Unit` match `start()` body; `suspend fun transcribe(wav: ByteArray, apiKeys: List<String>, baseUrl: String, model: String, ...): Pair<String, Int>` keeps signature plus `suspend`; `executeWithRotation` shape left to implementer within stated bounds; T4 owns all of `test()`, T1 owns only service catches, T5 preserves T1 lines. `runBlocking`/`async`/`Dispatchers` in the T4 test come from coroutines core (already on test classpath via the `-android` artifact) — no new dependency.

## Execution Handoff

**Plan complete and saved to `docs/superpowers/plans/2026-09-15-p1-correctness-races.md`. Two execution options:**

**1. Subagent-Driven (recommended)** - I dispatch a fresh subagent per task, review between tasks, fast iteration

**2. Inline Execution** - Execute tasks in this session using executing-plans, batch execution with checkpoints

**Which approach?**
