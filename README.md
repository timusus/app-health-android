# App Health Android SDK

Crash reporting and ANR detection for Android apps, built on OpenTelemetry.

## Why This SDK?

An open-source alternative to proprietary crash reporting services. Built on OpenTelemetry, so crashes go to your collector in a standard format.

- **JVM crash handling** - uncaught exceptions, stored to disk, reported on next launch
- **Coroutine crash handling** - exceptions that bypass the default handler
- **NDK crash handling** - native signals (SIGSEGV, SIGABRT, etc.) with backtraces
- **ANR detection** - watchdog thread + historical API 30+ data

## Installation

```kotlin
// build.gradle.kts
dependencies {
    implementation("com.simplecityapps:app-health-android:0.2.0")
}
```

## Quick Start

```kotlin
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()

        val openTelemetry = createOpenTelemetrySdk()
        AppHealth.init(context = this, openTelemetry = openTelemetry)
    }
}
```

That's it. The SDK automatically captures crashes and ANRs.

## What Gets Captured

### JVM Crashes

Uncaught exceptions on any thread. Stored to disk immediately (process is dying), reported on next launch.

```
Severity: ERROR
Body: "JVM Crash: NullPointerException: Cannot invoke method on null"

Attributes:
  crash.type: "jvm"
  exception.type: "java.lang.NullPointerException"
  exception.message: "Cannot invoke method on null"
  exception.stacktrace: "at com.example.MyClass.method(MyClass.kt:42)..."
  thread.name: "main"
```

### Coroutine Crashes

Uncaught exceptions in coroutines (via `CoroutineExceptionHandler` ServiceLoader). Same store-and-forward pattern.

```
Severity: ERROR
Body: "Coroutine Crash: IllegalStateException: Invalid state"

Attributes:
  crash.type: "coroutine"
  coroutine.name: "StandaloneCoroutine#1"
  exception.type: "java.lang.IllegalStateException"
  exception.stacktrace: "..."
```

### NDK Crashes

Native signal handlers for SIGSEGV, SIGABRT, SIGBUS, SIGFPE, SIGILL. Captures backtrace and fault address.

```
Severity: FATAL
Body: "Native Crash: SIGSEGV"

Attributes:
  crash.type: "native"
  signal: "SIGSEGV"
  fault.address: "0x00000000"
  backtrace: "#0 libapp.so!crash_function+0x10..."
```

### ANRs

Detected two ways:
1. **Watchdog thread** - pings main thread every second, reports if blocked >5s
2. **Historical API** (Android 11+) - reads `ActivityManager.getHistoricalProcessExitReasons()` on launch

```
Severity: ERROR
Body: "ANR Detected: Main thread blocked for 5000ms"

Attributes:
  anr.type: "watchdog"
  anr.timeout_ms: 5000
  anr.main_thread.stacktrace: "at android.os.MessageQueue.nativePollOnce..."
```

## Configuration

All features enabled by default. Disable what you don't need:

```kotlin
AppHealth.init(context, openTelemetry) {
    crashHandling = false         // JVM UncaughtExceptionHandler
    coroutineCrashHandling = false // Coroutine exceptions
    anrDetection = false          // ANR watchdog
    ndkCrashHandling = false      // Native signal handlers
}
```

## Setting Up OpenTelemetry for Android

OpenTelemetry on Android requires some mobile-specific configuration.

### Basic Setup

```kotlin
fun createOpenTelemetrySdk(): OpenTelemetrySdk {
    val resource = Resource.builder()
        .put(ServiceAttributes.SERVICE_NAME, "my-app")
        .put(ServiceAttributes.SERVICE_VERSION, BuildConfig.VERSION_NAME)
        .put("device.model.name", Build.MODEL)
        .put("device.manufacturer", Build.MANUFACTURER)
        .put("os.name", "Android")
        .put("os.version", Build.VERSION.SDK_INT.toString())
        .build()

    return OpenTelemetrySdk.builder()
        .setResource(resource)
        .setLoggerProvider(
            SdkLoggerProvider.builder()
                .setResource(resource)
                .addLogRecordProcessor(
                    BatchLogRecordProcessor.builder(
                        OtlpHttpLogRecordExporter.builder()
                            .setEndpoint("https://your-collector.example.com/v1/logs")
                            .build()
                    ).build()
                )
                .build()
        )
        .build()
}
```

### Mobile Considerations

**Use OTLP/HTTP, not gRPC** - simpler, fewer dependencies, works everywhere.

**Batch exports** - The SDK uses `BatchLogRecordProcessor` by default with sensible settings. For custom tuning:

```kotlin
BatchLogRecordProcessor.builder(exporter)
    .setMaxQueueSize(200)        // Buffer size
    .setMaxExportBatchSize(50)   // Batch size per export
    .setScheduleDelay(30, TimeUnit.SECONDS)  // Export interval
    .build()
```

**Flush on background** - Data in memory is lost if the app is killed. Flush when backgrounded:

```kotlin
class MyApp : Application(), DefaultLifecycleObserver {
    private lateinit var openTelemetry: OpenTelemetrySdk

    override fun onCreate() {
        super.onCreate()
        openTelemetry = createOpenTelemetrySdk()
        AppHealth.init(this, openTelemetry)
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun onStop(owner: LifecycleOwner) {
        // App going to background - flush telemetry
        openTelemetry.sdkLoggerProvider.forceFlush()
    }
}
```

**Handle no network gracefully** - OTLP exporters retry automatically, but crashes stored by AppHealth are reported on next launch regardless of network state.

## Additional Telemetry

This SDK focuses on crashes and ANRs. For other telemetry:

- **Network tracing**: Use [opentelemetry-okhttp-3.0](https://github.com/open-telemetry/opentelemetry-java-instrumentation)
- **Startup timing, navigation, lifecycle, frame metrics**: See [docs/RECIPES.md](docs/RECIPES.md) for ~20 line implementations

## API Reference

```kotlin
// Initialize (call once in Application.onCreate)
AppHealth.init(context, openTelemetry) { /* config */ }

// Check initialization state
AppHealth.isInitialized  // init() was called
AppHealth.isReady        // async init completed
AppHealth.awaitReady(timeoutMs = 5000)  // block until ready

// Force flush (useful in tests)
AppHealth.forceFlush()

// Trigger native crash (testing only)
AppHealth.triggerNativeCrashForTesting()
```

## Requirements

- Android 8.0+ (API 26)
- Kotlin 1.7+

## License

Apache 2.0 - see [LICENSE](LICENSE) for details.
