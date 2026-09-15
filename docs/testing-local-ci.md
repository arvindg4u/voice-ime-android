# Local + CI Testing

Toolchain lives under `/teamspace/studios/this_studio/.android-toolchain/`
(JDK 17 + Android SDK platform-34) — persistent, studio-local, nothing
installed globally. Export before every Gradle invocation:

```bash
export JAVA_HOME=/teamspace/studios/this_studio/.android-toolchain/jdk-17.0.20.1+1
export ANDROID_HOME=/teamspace/studios/this_studio/.android-toolchain/android-sdk
export ANDROID_SDK_ROOT=$ANDROID_HOME
export PATH=$JAVA_HOME/bin:$PATH
export GRADLE_USER_HOME=/teamspace/studios/this_studio/.gradle
```

Gradle proxy config lives in `$GRADLE_USER_HOME/gradle.properties`
(`systemProp.http[s].proxyHost=127.0.0.1`, port `18104`) — required on this
box, harmless elsewhere.

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

Baseline (2026-09-15): 106 tests, 0 failures — AudioRecorderTest 13,
ClipboardPolicyTest 18, ClipboardStoreTest 9, ImeLayoutPolicyTest 9,
LiveSessionGuardTest 3, LlmClientTest 53, RobolectricSetupTest 1.
