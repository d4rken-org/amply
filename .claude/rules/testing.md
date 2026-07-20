# Testing

## Framework

- **JUnit 5 (Jupiter)** for normal JVM unit tests.
- **JUnit 4** only for **Robolectric** tests — Robolectric does not support the JUnit 5 runner.
- **Kotest matchers** for assertions (`io.kotest.matchers.shouldBe`, `shouldThrow`, …).
- **Robolectric** for tests that need the Android framework.
- `kotlinx-coroutines-test` for coroutine tests.

Write pure JVM tests as JUnit 5 + Kotest; Robolectric tests stay on JUnit 4 (`@RunWith(RobolectricTestRunner::class)`)
but still use Kotest matchers. Truth is not a dependency — do not reintroduce it.

## What to Test

- Pure decision engines and mapping logic — this is where most value is. Existing examples:
  `SessionDecisionEngine`, `BootRecoveryEngine`, `BootRecoveryFlow`, `QuickFullChargeGesture`, `ChargePolicy`,
  `PixelChargingAdapter` (+ `…HardwareTest`), `ShizukuInstallationDetector`, `ChargingControlUserService`, `Logging`.
- Keep new charge/session decision logic in a pure unit so it can run on the JVM without a device.
- **Avoid `androidTest` instrumentation tests** where a JVM/Robolectric test can cover the behavior — the one
  existing instrumented test (`MainActivityTest`) is the exception, not the norm.

## Location

Tests mirror the main package structure under `app/src/test/java/eu/darken/amply/…`. Instrumented tests live under
`app/src/androidTest/java/…`.

## Normal Unit Tests (JUnit 5 + Kotest)

```kotlin
package eu.darken.amply.fullcharge.core

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class SessionDecisionEngineTest {

    @Test
    fun `disconnect after connection restores`() {
        decide(age = 10_000, connectedSeen = true, plugged = false, full = false) shouldBe
            SessionDecision.RESTORE_DISCONNECTED
    }
}
```

Use backtick test names. Prefer table-style cases that exercise the decision boundaries (timeouts, plugged/unplugged
transitions, full-vs-armed) directly against the pure engine.

## Robolectric Tests (JUnit 4)

For tests that need the Android framework, add Robolectric's annotations and use the JUnit 4 `@Test`:

```kotlin
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SomeAndroidDependentTest {
    @Test
    fun `behaviour requiring the Android framework`() { … }
}
```

`testOptions.unitTests.isIncludeAndroidResources = true` is set, so Robolectric tests can read resources.

## Running

```bash
./gradlew testFossDebugUnitTest
./gradlew testGplayDebugUnitTest
```

CI runs both flavors — a test that only compiles under one flavor breaks the build.
