# App Health Android SDK - Telemetry Specification

This document specifies all telemetry emitted by the App Health Android SDK, following [OpenTelemetry Semantic Conventions](https://opentelemetry.io/docs/specs/semconv/) format.

**SDK Version**: 1.0.0
**Instrumentation Name**: `com.simplecityapps.apphealth.android`
**Protocol**: OTLP/HTTP (JSON)

---

## Table of Contents

- [Resource Attributes](#resource-attributes)
- [Log Records](#log-records)
  - [Crash Logs](#crash-logs)
  - [ANR Logs](#anr-logs)
- [Export Configuration](#export-configuration)

---

## Resource Attributes

All telemetry signals include these resource attributes:

### Standard OTel Service Attributes

| Attribute | Type | Requirement Level | Description | Example |
|-----------|------|-------------------|-------------|---------|
| `service.name` | string | Required | Application package name, set during SDK initialization | `"com.example.myapp"` |

### Standard OTel Telemetry SDK Attributes

| Attribute | Type | Requirement Level | Description | Example |
|-----------|------|-------------------|-------------|---------|
| `telemetry.sdk.name` | string | Required | SDK identifier | `"app-health-android"` |
| `telemetry.sdk.version` | string | Required | SDK version | `"1.0.0"` |
| `telemetry.sdk.language` | string | Required | SDK implementation language | `"kotlin"` |

### Standard OTel OS Attributes

| Attribute | Type | Requirement Level | Description | Example |
|-----------|------|-------------------|-------------|---------|
| `os.name` | string | Required | Operating system name | `"Android"` |
| `os.type` | string | Required | Operating system type | `"linux"` |
| `os.version` | string | Required | Android SDK version from `Build.VERSION.SDK_INT` | `"34"` |

### Standard OTel Device Attributes

| Attribute | Type | Requirement Level | Description | Example |
|-----------|------|-------------------|-------------|---------|
| `device.model.name` | string | Required | Device model from `Build.MODEL` | `"Pixel 6"` |
| `device.manufacturer` | string | Required | Device manufacturer from `Build.MANUFACTURER` | `"Google"` |

### App-Specific Attributes

| Attribute | Type | Requirement Level | Description | Example |
|-----------|------|-------------------|-------------|---------|
| `app.version` | string | Required | Application version name from PackageManager | `"1.0.0"` |
| `app.version.code` | long | Required | Application version code from PackageManager | `42` |

**Note**: Resource attributes are set by the app when configuring OpenTelemetry, not by AppHealth. AppHealth is pure instrumentation - it emits logs to the app-provided OpenTelemetry SDK.

---

## Log Records

### Crash Logs

#### JVM Crash

**Severity**: `ERROR`

**Body Format**: `"JVM Crash: {ExceptionSimpleName}: {message}"`

| Attribute | Type | Requirement Level | Description | Example |
|-----------|------|-------------------|-------------|---------|
| `crash.type` | string | Required | Always `"jvm"` | `"jvm"` |
| `exception.type` | string | Required | Fully qualified exception class name | `"java.lang.NullPointerException"` |
| `exception.message` | string | Required | Exception message (empty string if null) | `"Cannot invoke method on null"` |
| `exception.stacktrace` | string | Required | Full stack trace | `"at com.example.MyClass.method(MyClass.kt:42)\n..."` |
| `thread.name` | string | Required | Name of the thread where crash occurred | `"main"` |
| `thread.id` | long | Required | Thread ID | `1` |

**Trigger**: Uncaught exception handler invoked.

**Note**: The log body uses the simple class name (e.g., `NullPointerException`), while `exception.type` contains the fully qualified name (e.g., `java.lang.NullPointerException`).

---

#### Coroutine Crash

**Severity**: `ERROR`

**Body Format**: `"Coroutine Crash: {ExceptionSimpleName}: {message}"`

| Attribute | Type | Requirement Level | Description | Example |
|-----------|------|-------------------|-------------|---------|
| `crash.type` | string | Required | Always `"coroutine"` | `"coroutine"` |
| `exception.type` | string | Required | Fully qualified exception class name | `"kotlinx.coroutines.JobCancellationException"` |
| `exception.message` | string | Required | Exception message (empty string if null) | `"Job was cancelled"` |
| `exception.stacktrace` | string | Required | Full stack trace | `"at kotlinx.coroutines..."` |
| `thread.name` | string | Required | Name of the thread where crash occurred | `"DefaultDispatcher-worker-1"` |
| `thread.id` | long | Required | Thread ID | `42` |
| `coroutine.name` | string | Required | Coroutine name from context (or `"unknown"`) | `"StandaloneCoroutine#1"` |
| `coroutine.cancelled` | boolean | Required | Whether coroutine was cancelled | `false` |

**Trigger**: `CoroutineExceptionHandler` invoked.

---

#### Native (NDK) Crash

**Severity**: `FATAL`

**Body Format**: `"Native Crash: {signal}"`

| Attribute | Type | Requirement Level | Description | Example |
|-----------|------|-------------------|-------------|---------|
| `crash.type` | string | Required | Always `"native"` | `"native"` |
| `signal` | string | Required | Unix signal name | `"SIGSEGV"` |
| `fault.address` | string | Required | Memory address that caused the fault | `"0x00000000"` |
| `backtrace` | string | Required | Native stack frames | `"#0 0x7fff5fbff8c0 in crash_function..."` |

**Supported Signals**: `SIGSEGV`, `SIGABRT`, `SIGBUS`, `SIGFPE`, `SIGILL`

**Trigger**: Crash data file read on next cold start after native crash.

---

### ANR Logs

#### Real-time ANR (Watchdog)

**Severity**: `ERROR`

**Body Format**: `"ANR Detected: Main thread blocked for {timeout_ms}ms"`

| Attribute | Type | Requirement Level | Description | Example |
|-----------|------|-------------------|-------------|---------|
| `anr.type` | string | Required | Always `"watchdog"` | `"watchdog"` |
| `anr.timeout_ms` | long | Required | Detection threshold in milliseconds | `5000` |
| `anr.main_thread.stacktrace` | string | Required | Full stack trace of blocked main thread | `"\tat android.os.MessageQueue.nativePollOnce..."` |
| `anr.other_threads.stacktraces` | string | Required | Stack traces of up to 10 other threads | `"Thread: Thread-2 (WAITING)\n\tat java.lang.Object.wait..."` |

**Trigger**: Main thread unresponsive for 5+ seconds (configurable).

**Detection Parameters**:
- Default timeout: 5000ms
- Check interval: 1000ms

---

#### Historical ANR (API 30+)

**Severity**: `ERROR`

**Body Format**: `"Historical ANR: {description}"`

| Attribute | Type | Requirement Level | Description | Example |
|-----------|------|-------------------|-------------|---------|
| `anr.type` | string | Required | Always `"historical"` | `"historical"` |
| `anr.timestamp` | long | Required | Unix timestamp when ANR occurred | `1706616000000` |
| `anr.description` | string | Required | System-provided ANR description (empty string if null) | `"Input dispatching timed out"` |
| `anr.pid` | long | Required | Process ID at time of ANR | `12345` |

**Trigger**: Cold start on Android API 30+ reads historical ANR data from `ActivityManager.getHistoricalProcessExitReasons()`.

---

## Export Configuration

### Endpoints

| Signal | Endpoint Path |
|--------|---------------|
| Traces | `{base_url}/v1/traces` |
| Logs | `{base_url}/v1/logs` |
| Metrics | `{base_url}/v1/metrics` |

### Batching

| Parameter | Value |
|-----------|-------|
| Max Queue Size | 200 items |
| Max Batch Size | 50 items |
| Export Interval | 30 seconds |
| Request Timeout | 10 seconds |

### Protocol

- Format: OTLP/HTTP with JSON encoding
- No gRPC dependency
- Automatic retry on transient failures

---

## Constants Reference

```kotlin
// Instrumentation
const val INSTRUMENTATION_NAME = "com.simplecityapps.apphealth.android"
const val INSTRUMENTATION_VERSION = "1.0.0"

// Export
const val MAX_QUEUE_SIZE = 200
const val MAX_BATCH_SIZE = 50
const val EXPORT_INTERVAL_SECONDS = 30L

// ANR Detection
const val DEFAULT_ANR_TIMEOUT_MS = 5000L
const val DEFAULT_ANR_CHECK_INTERVAL_MS = 1000L
```

---

## Appendix: Semantic Conventions Alignment

This SDK follows OpenTelemetry semantic conventions:

| Convention | Status | Notes |
|------------|--------|-------|
| [Exception Attributes](https://opentelemetry.io/docs/specs/semconv/attributes-registry/exception/) | Full | Uses `exception.type`, `exception.message`, `exception.stacktrace` |
| [Thread Attributes](https://opentelemetry.io/docs/specs/semconv/attributes-registry/thread/) | Full | Uses `thread.name`, `thread.id` |
| [Device Attributes](https://opentelemetry.io/docs/specs/semconv/attributes-registry/device/) | Full | Uses `device.model.name`, `device.manufacturer` |
| [OS Attributes](https://opentelemetry.io/docs/specs/semconv/attributes-registry/os/) | Full | Uses `os.name`, `os.type`, `os.version` |
| [Service Attributes](https://opentelemetry.io/docs/specs/semconv/attributes-registry/service/) | Full | Uses `service.name` |
| [Telemetry SDK Attributes](https://opentelemetry.io/docs/specs/semconv/attributes-registry/telemetry/) | Full | Uses `telemetry.sdk.name`, `telemetry.sdk.version`, `telemetry.sdk.language` |

### SDK-Specific Attributes

The following attributes are SDK-specific extensions:

| Attribute | Description |
|-----------|-------------|
| `app.version`, `app.version.code` | Application version info |
| `crash.type` | Crash categorization (`jvm`, `coroutine`, `native`) |
| `anr.type`, `anr.timeout_ms`, `anr.timestamp`, `anr.description`, `anr.pid` | ANR details |
| `anr.main_thread.stacktrace`, `anr.other_threads.stacktraces` | ANR diagnostics |
| `coroutine.name`, `coroutine.cancelled` | Coroutine crash context |
