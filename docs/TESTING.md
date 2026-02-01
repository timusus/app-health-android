# Testing Strategy

This document describes the testing approach for the App Health Android SDK.

## Philosophy

**Test at the boundaries, not the internals.**

We use **fakes** over **mocks** wherever possible:
- Fakes test behavior (what was emitted)
- Mocks test implementation (what methods were called)

## Test Structure

```
apphealth/src/test/kotlin/
  com/simplecityapps/apphealth/android/
    fakes/
      InMemoryTelemetry.kt        # Wraps OTel SDK testing exporters
    CrashHandlerTest.kt
    AnrWatchdogTest.kt
    ...
```

## Running Tests

```bash
./gradlew :apphealth:test
```

## Test Fakes

### InMemoryTelemetry

Wraps OpenTelemetry's `InMemoryLogRecordExporter` and `InMemorySpanExporter`:

```kotlin
val telemetry = InMemoryTelemetry()

// Use real OTel logger/tracer
val handler = CrashHandler(telemetry.logger, null)
handler.uncaughtException(thread, exception)

// Assert on actual captured telemetry
val logs = telemetry.getLogRecords()
assertEquals(1, logs.size)
assertEquals(Severity.ERROR, logs[0].severity)
assertTrue(logs[0].body.asString().contains("RuntimeException"))
```

## Test Coverage

| Component | Unit Test |
|-----------|-----------|
| JVM Crash Handler | `CrashHandlerTest` |
| Coroutine Exception Handler | `CoroutineExceptionHandlerTest` |
| ANR Watchdog | `AnrWatchdogTest` |
| NDK Crash Reporter | `NdkCrashReporterTest` |
| Crash Storage | `CrashStorageTest` |

## Writing New Tests

Use `InMemoryTelemetry` for anything that emits telemetry:

```kotlin
@Test
fun `my feature emits correct log`() {
    val telemetry = InMemoryTelemetry()

    val myComponent = MyComponent(telemetry.logger)
    myComponent.doSomething()

    val logs = telemetry.getLogRecords()
    assertEquals(1, logs.size)
    assertEquals("expected.event", logs[0].body.asString())
}
```
