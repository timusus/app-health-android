# Testing Strategy

This document describes the testing approach for the App Health Android SDK.

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
apphealth/src/test/kotlin/
  com/simplecityapps/apphealth/android/
    fakes/
      FakeSharedPreferences.kt    # In-memory SharedPreferences
      InMemoryTelemetry.kt        # Wraps OTel SDK testing exporters
    CrashHandlerTest.kt
    ...

sample/src/androidTest/kotlin/
  com/simplecityapps/apphealth/sample/
    MockOtlpCollector.kt          # HTTP server capturing OTLP requests
    AppHealthE2ETest.kt
    CrashHandlerE2ETest.kt
    NetworkInterceptorE2ETest.kt
```

## Running Tests

```bash
# Unit tests (JVM)
./gradlew :apphealth:test

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
prefs.edit().putString("key", "value").apply()
assertEquals("value", prefs.getString("key", null))
```

### MockOtlpCollector

HTTP server for E2E tests that captures OTLP requests:

```kotlin
val collector = MockOtlpCollector()
collector.start()
collector.expectSpans(1)

// Use the shared OTel instance for custom telemetry
val tracer = AppHealth.openTelemetry.getTracer("test")
val span = tracer.spanBuilder("test-span").startSpan()
span.end()

assertTrue(collector.awaitSpans(timeoutSeconds = 30))
```

## Test Coverage

| Component | Unit Test | E2E Test |
|-----------|-----------|----------|
| JVM Crash Handler | ✓ `CrashHandlerTest` | - |
| Coroutine Exception Handler | ✓ `CoroutineExceptionHandlerTest` | - |
| ANR Watchdog | ✓ `AnrWatchdogTest` | - |
| Network Interceptor | ✓ `NetworkInterceptorTest` | ✓ `NetworkInterceptorE2ETest` |
| Startup Timing | ✓ `StartupTracerTest` | - |
| Lifecycle Tracking | ✓ `LifecycleTrackerTest` | - |
| Frame Metrics | ✓ `JankTrackerTest` | - |
| Navigation Tracking | ✓ `NavigationTrackerTest` | - |
| Custom Events/Spans | - | ✓ `AppHealthE2ETest` |
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

    AppHealth.someFeature()

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
