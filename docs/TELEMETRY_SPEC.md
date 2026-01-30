# App Health Android SDK - Telemetry Specification

This document specifies all telemetry emitted by the App Health Android SDK, following [OpenTelemetry Semantic Conventions](https://opentelemetry.io/docs/specs/semconv/) format.

**SDK Version**: 1.0.0
**Instrumentation Name**: `com.simplecityapps.apphealth.android`
**Protocol**: OTLP/HTTP (JSON)

---

## Table of Contents

- [Resource Attributes](#resource-attributes)
- [Spans](#spans)
  - [App Startup Spans](#app-startup-spans)
  - [HTTP Client Spans](#http-client-spans)
  - [Screen View Spans](#screen-view-spans)
  - [Custom Spans](#custom-spans)
- [Metrics](#metrics)
  - [Frame Metrics](#frame-metrics)
- [Log Records](#log-records)
  - [Crash Logs](#crash-logs)
  - [ANR Logs](#anr-logs)
  - [Lifecycle Events](#lifecycle-events)
  - [Custom Events](#custom-events)
- [Session Management](#session-management)
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
| `session.id` | string | Required | UUID identifying the current user session | `"550e8400-e29b-41d4-a716-446655440000"` |
| `device.installation.id` | string | Optional | Random UUID for device correlation. Persists until app uninstall. Can be disabled via `installationIdEnabled = false` for GDPR compliance. | `"a1b2c3d4-e5f6-7890-abcd-ef1234567890"` |

### Custom Attributes via OpenTelemetry

For custom spans, events, and attributes, use the app-provided OpenTelemetry SDK:

```kotlin
// Use the same SDK you passed to AppHealth.init()
val tracer = openTelemetry.getTracer("my-feature")
val span = tracer.spanBuilder("custom-operation").startSpan()
// AppHealth automatically adds session.id to all spans
```

---

## Spans

### App Startup Spans

#### `app.startup` - Time to Initial Display (TTID)

Measures cold start duration until first activity is displayed.

**Span Kind**: `INTERNAL`

| Attribute | Type | Requirement Level | Description | Example |
|-----------|------|-------------------|-------------|---------|
| `startup.type` | string | Required | Type of startup. Always `"cold"` for this span. | `"cold"` |
| `startup.duration_ms` | long | Required | Duration from process start to first `onActivityResumed()` in milliseconds | `1250` |

**Trigger**: First `onActivityResumed()` callback after process start.

---

#### `app.startup.full` - Time to Full Display (TTFD)

Measures cold start duration until app reports fully drawn.

**Span Kind**: `INTERNAL`

| Attribute | Type | Requirement Level | Description | Example |
|-----------|------|-------------------|-------------|---------|
| `startup.type` | string | Required | Type of startup. Always `"cold"` for this span. | `"cold"` |
| `startup.duration_ms` | long | Required | Duration from process start to `reportFullyDrawn()` call in milliseconds | `2100` |
| `startup.fully_drawn` | boolean | Required | Always `true` for this span | `true` |

**Trigger**: Application calls `AppHealth.reportFullyDrawn()`.

---

### HTTP Client Spans

#### `HTTP {method}`

Captures outbound HTTP requests via OkHttp interceptor.

**Span Kind**: `CLIENT`

**Span Name Format**: `"HTTP {method}"` where `{method}` is the HTTP method (GET, POST, PUT, DELETE, etc.)

| Attribute | Type | Requirement Level | Description | Example |
|-----------|------|-------------------|-------------|---------|
| `http.request.method` | string | Required | HTTP request method | `"GET"` |
| `url.full` | string | Required | Sanitized request URL (IDs replaced with `{id}`, query params stripped) | `"https://api.example.com/users/{id}"` |
| `url.path` | string | Required | URL path only, encoded | `"/users/123"` |
| `server.address` | string | Required | Host name of the request target | `"api.example.com"` |
| `server.port` | long | Required | Port number of the request target | `443` |
| `http.response.status_code` | long | Conditionally Required | HTTP response status code. Required if response received. | `200` |
| `http.request.body.size` | long | Recommended | Request body size in bytes. Only set if > 0. | `256` |
| `http.response.body.size` | long | Recommended | Response body size in bytes. Only set if > 0. | `1024` |

**Span Status**:
- `ERROR` if `http.response.status_code` >= 400, with message `"HTTP {status_code}"`
- `ERROR` with exception recorded if `IOException` occurs
- `OK` otherwise

**Exception Recording**: On `IOException`, the span calls `recordException(e)` which adds:
- `exception.type`: Exception class name
- `exception.message`: Exception message
- `exception.stacktrace`: Full stack trace

**URL Sanitization Rules**:
1. UUIDs are replaced with `{id}`
2. Numeric path segments are replaced with `{id}`
3. Query parameters are stripped entirely
4. Custom sanitization available via `urlSanitizer` parameter in `AppHealth.init()`

---

### Screen View Spans

#### `screen.view`

Tracks navigation between screens in Jetpack Compose Navigation.

**Span Kind**: `INTERNAL`

| Attribute | Type | Requirement Level | Description | Example |
|-----------|------|-------------------|-------------|---------|
| `screen.name` | string | Required | Normalized route name (parameters replaced with `{id}`) | `"profile/{id}"` |

**Trigger**: Navigation change detected via `NavController.currentBackStackEntryFlow`.

**Route Normalization Rules**:
1. UUIDs in routes are replaced with `{id}`
2. Numeric segments in routes are replaced with `{id}`
3. Already parameterized routes (e.g., `{userId}`) are preserved as-is

---

### Custom Spans

Create custom spans using the shared OpenTelemetry instance:

**Span Kind**: `INTERNAL`

**Example**:
```kotlin
val tracer = openTelemetry.getTracer("my-feature")
val span = tracer.spanBuilder("load_podcast_list").startSpan()
try {
    // ... perform work ...
} finally {
    span.end()
}
```

Custom spans automatically inherit resource attributes (session.id, device info, etc.).

---

## Metrics

### Frame Metrics

Reported every 30 seconds per activity.

#### `frames.total`

**Instrument Type**: Counter
**Unit**: `{frame}`
**Description**: Total frames rendered

| Attribute | Type | Requirement Level | Description | Example |
|-----------|------|-------------------|-------------|---------|
| `screen.name` | string | Required | Current activity class simple name | `"MainActivity"` |

---

#### `frames.slow`

**Instrument Type**: Counter
**Unit**: `{frame}`
**Description**: Frames exceeding 16ms render time (missed vsync)

| Attribute | Type | Requirement Level | Description | Example |
|-----------|------|-------------------|-------------|---------|
| `screen.name` | string | Required | Current activity class simple name | `"MainActivity"` |

**Threshold**: > 16ms frame duration

---

#### `frames.frozen`

**Instrument Type**: Counter
**Unit**: `{frame}`
**Description**: Frames exceeding 700ms render time (UI freeze)

| Attribute | Type | Requirement Level | Description | Example |
|-----------|------|-------------------|-------------|---------|
| `screen.name` | string | Required | Current activity class simple name | `"MainActivity"` |

**Threshold**: > 700ms frame duration

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

### Lifecycle Events

#### App Foreground

**Severity**: `INFO`

**Body**: `"app.foreground"`

| Attribute | Type | Requirement Level | Description | Example |
|-----------|------|-------------------|-------------|---------|
| `event.name` | string | Required | Always `"app.foreground"` | `"app.foreground"` |

**Trigger**: `ProcessLifecycleOwner` transitions to `ON_START`.

---

#### App Background

**Severity**: `INFO`

**Body**: `"app.background"`

| Attribute | Type | Requirement Level | Description | Example |
|-----------|------|-------------------|-------------|---------|
| `event.name` | string | Required | Always `"app.background"` | `"app.background"` |
| `app.foreground.duration_ms` | long | Required | Time spent in foreground in milliseconds | `120000` |

**Trigger**: `ProcessLifecycleOwner` transitions to `ON_STOP`.

---

### Custom Events

Create custom log events using the shared OpenTelemetry instance:

**Severity**: `INFO`

**Example**:
```kotlin
val logger = openTelemetry.logsBridge.loggerBuilder("my-feature").build()
logger.logRecordBuilder()
    .setSeverity(Severity.INFO)
    .setBody("playback_started")
    .setAllAttributes(Attributes.of(
        AttributeKey.stringKey("podcast_id"), "abc-123",
        AttributeKey.longKey("duration_ms"), 3600000L
    ))
    .emit()
```

Custom events automatically inherit resource attributes (session.id, device info, etc.).

---

## Session Management

### Session Lifecycle

| State | Behavior |
|-------|----------|
| Cold Start | New UUID generated and persisted to SharedPreferences |
| Foreground Resume | Existing session continues if < 30 minutes since background |
| Session Timeout | New UUID generated if > 30 minutes since last foreground |

**Session Timeout**: 30 minutes (1,800,000ms)

### Session ID

- Format: UUID v4
- Storage: SharedPreferences (`telemetry_session`)
- Key: `session_id`
- Attached to all telemetry as resource attribute `session.id`

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

// Frame Metrics
const val FRAME_REPORT_INTERVAL_MS = 30_000L
const val SLOW_FRAME_THRESHOLD_MS = 16
const val FROZEN_FRAME_THRESHOLD_MS = 700

// Session
const val SESSION_TIMEOUT_MS = 30 * 60 * 1000L  // 30 minutes
```

---

## Appendix: Semantic Conventions Alignment

This SDK follows OpenTelemetry semantic conventions:

| Convention | Status | Notes |
|------------|--------|-------|
| [HTTP Client Spans](https://opentelemetry.io/docs/specs/semconv/http/http-spans/) | Full | Uses `http.request.method`, `http.response.status_code`, `url.full`, `url.path`, `server.address`, `server.port` |
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
| `session.id` | User session identifier (UUID) |
| `device.installation.id` | Persistent device identifier (UUID), opt-out via `installationIdEnabled = false` |
| `app.version`, `app.version.code` | Application version info |
| `crash.type` | Crash categorization (`jvm`, `coroutine`, `native`) |
| `startup.type`, `startup.duration_ms`, `startup.fully_drawn` | Startup metrics |
| `anr.type`, `anr.timeout_ms`, `anr.timestamp`, `anr.description`, `anr.pid` | ANR details |
| `anr.main_thread.stacktrace`, `anr.other_threads.stacktraces` | ANR diagnostics |
| `coroutine.name`, `coroutine.cancelled` | Coroutine crash context |
| `screen.name` | Navigation/jank tracking |
| `app.foreground.duration_ms` | Foreground session duration |
| `event.name` | Lifecycle and custom event identifier |
