# Testing Strategy

This document describes the testing approach for the Android Telemetry SDK.

## Philosophy

**Test at the boundaries, not the internals.**

For an SDK, the boundaries are:
- **Inbound**: The host application (our public API)
- **Outbound**: The OTLP collector (HTTP endpoints)

We use **fakes** over **mocks** wherever possible:
- Fakes test behavior (what was emitted)
- Mocks test implementation (what methods were called)

## Test Structure

```
telemetry/src/test/kotlin/
  com/simplecityapps/telemetry/android/
    fakes/
      FakeSharedPreferences.kt    # In-memory SharedPreferences
      InMemoryTelemetry.kt        # Wraps OTel SDK testing exporters
    SessionManagerTest.kt
    CrashHandlerTest.kt
    ...

sample/src/androidTest/kotlin/
  com/simplecityapps/telemetry/sample/
    MockOtlpCollector.kt          # HTTP server capturing OTLP requests
    TelemetryE2ETest.kt
    CrashHandlerE2ETest.kt
    NetworkInterceptorE2ETest.kt
```

## Running Tests

```bash
# Unit tests (JVM)
./gradlew :telemetry:test

# E2E tests (requires device/emulator)
./gradlew :sample:connectedAndroidTest
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

### FakeSharedPreferences

In-memory implementation for testing persistence:

```kotlin
val prefs = FakeSharedPreferences()
prefs.edit().putString("session_id", "abc-123").apply()

val manager = SessionManager(contextWithFakePrefs)
assertEquals("abc-123", manager.sessionId)
```

### MockOtlpCollector

HTTP server for E2E tests that captures OTLP requests:

```kotlin
val collector = MockOtlpCollector()
collector.start()
collector.expectLogs(1)

Telemetry.logEvent("test.event")

assertTrue(collector.awaitLogs(timeoutSeconds = 30))
assertTrue(collector.hasLogContaining("test.event"))
```

## Test Coverage

| Component | Unit Test | E2E Test |
|-----------|-----------|----------|
| Session Management | ✓ `SessionManagerTest` | - |
| JVM Crash Handler | ✓ `CrashHandlerTest` | ✓ `CrashHandlerE2ETest` |
| Coroutine Exception Handler | ✓ `CoroutineExceptionHandlerTest` | - |
| ANR Watchdog | ✓ `AnrWatchdogTest` | - |
| Network Interceptor | ✓ `NetworkInterceptorTest` | ✓ `NetworkInterceptorE2ETest` |
| Startup Timing | ✓ `StartupTracerTest` | - |
| Lifecycle Tracking | ✓ `LifecycleTrackerTest` | - |
| Frame Metrics | ✓ `JankTrackerTest` | - |
| Navigation Tracking | ✓ `NavigationTrackerTest` | - |
| Custom Events/Spans | - | ✓ `TelemetryE2ETest` |
| NDK Crash Reporter | ✓ `NdkCrashReporterTest` | - |
| NDK Signal Handler | - | Manual (requires native crash) |

## Writing New Tests

### Unit Tests

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

### E2E Tests

Use `MockOtlpCollector` to verify telemetry reaches the backend:

```kotlin
@Test
fun myFeatureReachesCollector() {
    collector.expectLogs(1)

    Telemetry.someFeature()

    assertTrue(collector.awaitLogs(timeoutSeconds = 30))
}
```

## Sample App

The `sample/` module provides manual testing:

```bash
./gradlew :sample:installDebug
```

Buttons to trigger:
- Custom events
- Custom spans
- Network requests
- JVM crashes
- NDK crashes (placeholder)
