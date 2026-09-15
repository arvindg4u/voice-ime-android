# Robolectric Local + CI Testing Setup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Enable Robolectric so unit tests run against real Android framework behavior on JVM, locally and in CI, with zero emulator.

**Architecture:** Add the Robolectric dependency + `testOptions.unitTests.isIncludeAndroidResources` flag so `src/test` gets real `Context`/`SharedPreferences`/resources via shadows. Prove it with one passing Robolectric test over an existing class, then mirror the exact same `./gradlew testDebugUnitTest` command locally and in CI. No DI framework, no new test framework — keep JUnit4 + existing `forDirectory`/`forContext` seams.

**Tech Stack:** Robolectric 4.12.1, JUnit 4.13.2 (existing), AGP 8.5.2, JDK 17, `org.json:json` (existing test dep)

**Spec:** User request — Robolectric for fast headless local AI-agent testing with local/CI parity via `./gradlew :app:testDebugUnitTest` and XML reports in `app/build/test-results/`.

## Global Constraints

- minSdk 26, targetSdk/compileSdk 34 — Robolectric must support SDK 34 shadows.
- JDK 17 locally and in CI (`actions/setup-java@v4`, temurin 17) — no toolchain change.
- `RepositoriesMode.FAIL_ON_PROJECT_REPOS` — no new repositories; Robolectric must come from google()/mavenCentral().
- Existing pure-JVM tests must keep passing unmodified.
- No emulator, no `src/androidTest`, no new CI runners.

---

### Task 1: Add Robolectric dependency + resource flag

**Files:**
- Modify: `app/build.gradle.kts:51-67`

**Interfaces:**
- Consumes: existing `testOptions { unitTests.isReturnDefaultValues = true }` block.
- Produces: `testOptions` with Android resources enabled + `testImplementation("org.robolectric:robolectric:4.12.1")` on the test classpath.

- [ ] **Step 1: Write the failing test** — no test code yet; instead verify the current state fails the goal: confirm no Robolectric artifact is resolvable. Run: `grep -r "robolectric" app/build.gradle.kts settings.gradle.kts build.gradle.kts` Expected: no output (exit 1) — proves Robolectric is absent.

- [ ] **Step 2: Add the dependency and flag**

```kotlin
testOptions {
    unitTests.isReturnDefaultValues = true
    unitTests.isIncludeAndroidResources = true
}
```

```kotlin
testImplementation("org.robolectric:robolectric:4.12.1")
```

Place the `testImplementation` line after the existing `testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")` line. Robolectric 4.12.1 supports SDK 34 shadows and resolves from mavenCentral — no repo changes needed under `FAIL_ON_PROJECT_REPOS`.

- [ ] **Step 3: Verify the edit**

Run: `grep -n "robolectric\|isIncludeAndroidResources" app/build.gradle.kts`
Expected: two lines — the flag inside `testOptions` and the `testImplementation` line.

- [ ] **Step 4: Commit**

```bash
git add app/build.gradle.kts
git commit -m "test: add Robolectric with Android resources for unit tests"
```

---

### Task 2: Prove Robolectric works — real SharedPreferences round-trip test

**Files:**
- Create: `app/src/test/java/com/voiceime/RobolectricSetupTest.kt`
- Uses (read-only): `app/src/main/java/com/voiceime/ClipboardHistoryActivity.kt:157-175` (companion `PREFS`/`KEY_ENABLED`/`isEnabled`, pure `SharedPreferences` logic — no Activity launch needed)

**Interfaces:**
- Consumes: Task 1's Robolectric classpath + `isIncludeAndroidResources`.
- Produces: proof that `RuntimeEnvironment.getApplication()` returns a working `Context` with real `SharedPreferences` on JVM.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.voiceime

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RobolectricSetupTest {

    @Test
    fun `real SharedPreferences round-trips through companion helpers`() {
        // Arrange
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.getSharedPreferences(
            ClipboardHistoryActivity.PREFS,
            android.content.Context.MODE_PRIVATE,
        ).edit().putBoolean(ClipboardHistoryActivity.KEY_ENABLED, true).apply()

        // Act
        val enabled = ClipboardHistoryActivity.isEnabled(context)

        // Assert
        assertEquals(true, enabled)
    }
}
```

Note: `androidx.test:core` comes transitively via Robolectric — no extra dep. `@Config(sdk = [34])` pins the shadow to the app's `targetSdk 34`.

- [ ] **Step 2: Run it to verify it passes (and would fail without Robolectric)**

Run: `./gradlew :app:testDebugUnitTest --tests "com.voiceime.RobolectricSetupTest" --stacktrace`
Expected: PASS, `BUILD SUCCESSFUL`. Sanity check it exercises shadows, not stubs: temporarily remove `@RunWith(RobolectricTestRunner::class)` and re-run — expected FAIL (`isReturnDefaultValues` returns default `false`, `assertEquals(true, ...)` fails). Restore the annotation after.

- [ ] **Step 3: Run the FULL suite to prove nothing regressed**

Run: `./gradlew :app:testDebugUnitTest --stacktrace`
Expected: all existing tests (`AudioRecorderTest`, `ClipboardPolicyTest`, `ClipboardStoreTest`, `ImeLayoutPolicyTest`, `LiveSessionGuardTest`, `LlmClientTest`) + new test PASS.

- [ ] **Step 4: Verify the XML report exists for agent parsing**

Run: `ls app/build/test-results/testDebugUnitTest/ | grep -i robolectric`
Expected: `TEST-com.voiceime.RobolectricSetupTest.xml`. Agents parse assertions from these files — confirm `<testsuite>` contains `tests="1" failures="0" errors="0"`.

- [ ] **Step 5: Commit**

```bash
git add app/src/test/java/com/voiceime/RobolectricSetupTest.kt
git commit -m "test: prove Robolectric shadows with SharedPreferences round-trip"
```

---

### Task 3: Document the local/CI loop (agent-runbook)

**Files:**
- Create: `docs/testing-local-ci.md`

**Interfaces:**
- Consumes: Task 2's verified commands and report paths.
- Produces: the single canonical command agents run locally and CI runs remotely — identical.

- [ ] **Step 1: Write the runbook**

```markdown
# Local + CI Testing

Single command, identical locally and on CI (JDK 17 required locally —
CI uses `actions/setup-java@v4`, temurin 17):

./gradlew :app:testDebugUnitTest --stacktrace

Targeted run (fastest feedback loop):

./gradlew :app:testDebugUnitTest --tests "com.voiceime.<TestClass>" --stacktrace

Parse results (no screenshots needed):

app/build/test-results/testDebugUnitTest/TEST-*.xml

Each file is a `<testsuite tests="N" failures="F" errors="E">` — green iff
`failures="0" errors="0"`.

Rules:
- New tests go in `src/test` (JVM + Robolectric), never `src/androidTest`.
- Use `@RunWith(RobolectricTestRunner::class)` + `@Config(sdk = [34])`
  only when the test needs real framework behavior (Context,
  SharedPreferences, resources). Pure-logic tests stay plain JUnit —
  faster, no shadows.
- Prefer the existing seams (`ClipboardStore.forDirectory`,
  `ApplicationProvider.getApplicationContext()`) over mocks; reach for
  MockK/Mockito-Kotlin only when isolating network or hardware.
- CI (`.github/workflows/android.yml`, `release.yml`) runs the same
  `testDebugUnitTest` task — if it passes locally on JDK 17, it passes on CI.
```

- [ ] **Step 2: Verify the doc's commands are accurate**

Run: `grep -c "testDebugUnitTest" docs/testing-local-ci.md`
Expected: `4` (one per command mention). Cross-check the CI filenames exist: `ls .github/workflows/android.yml .github/workflows/release.yml`.

- [ ] **Step 3: Commit**

```bash
git add docs/testing-local-ci.md
git commit -m "docs: document Robolectric local/CI testing loop"
```

---

## Self-Review

**1. Spec coverage:**
- "Robolectric runs on JVM, no emulator" → Task 1 (dep) + Task 2 (`@RunWith(RobolectricTestRunner)`, `@Config(sdk=[34])`).
- "Same command locally and on CI" → Task 3 runbook pins `./gradlew :app:testDebugUnitTest`; verified both workflows already run exactly that task.
- "Parse XML reports" → Task 2 Step 4 verifies `TEST-*.xml` exists with green counters; Task 3 documents the path.
- "Fast/headless/programmable" → targeted `--tests` filter documented; no emulator/ADB involved anywhere.

**2. Placeholder scan:** no TBD/TODO; every step has exact code, exact commands, exact expected output. Version `4.12.1` pinned (not "latest"). SDK `[34]` pinned to app's targetSdk. No DI introduced — plan explicitly keeps existing seams.

**3. Type consistency:** `ClipboardHistoryActivity.PREFS` (`String`), `KEY_ENABLED` (`String`), `isEnabled(Context): Boolean` — match `ClipboardHistoryActivity.kt:157-165` as read. `ApplicationProvider.getApplicationContext<android.content.Context>()` matches androidx.test API transitively provided by Robolectric. `sdk = [34]` is `IntArray` — correct Kotlin for Robolectric `@Config`.

## Execution Handoff

**Plan complete and saved to `docs/superpowers/plans/2026-09-15-robolectric-testing-setup.md`. Two execution options:**

**1. Subagent-Driven (recommended)** - I dispatch a fresh subagent per task, review between tasks, fast iteration

**2. Inline Execution** - Execute tasks in this session using executing-plans, batch execution with checkpoints

**Which approach?**
