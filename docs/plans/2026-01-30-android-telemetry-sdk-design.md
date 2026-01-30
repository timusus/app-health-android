# Android Telemetry SDK Design

## Overview

A lightweight Android SDK that captures observability signals (crashes, ANRs, performance, navigation) and exports them via OTLP/HTTP to a self-hosted SigNoz instance.

**Package**: `com.simplecityapps.telemetry.android`
**Min API**: 26 (Oreo)

## Core Principles

- Never crash the host app — all SDK code wrapped in try/catch
- Never block the main thread — all I/O on background dispatcher
- Fail silently — drop telemetry on export failure rather than retry aggressively
- Thread-safe — SDK methods callable from any thread

## Architecture

```
┌─────────────────────────────────────────────────────────┐
│                    Telemetry.kt                         │
│              (Public API - Singleton)                   │
└─────────────────────────────────────────────────────────┘
                          │
        ┌─────────────────┼─────────────────┐
        ▼                 ▼                 ▼
┌──────────────┐  ┌──────────────┐  ┌──────────────┐
│ Collectors   │  │   Context    │  │   Export     │
│              │  │              │  │              │
│ CrashHandler │  │SessionManager│  │ OtelConfig   │
│ AnrWatchdog  │  │(session.id,  │  │ (BatchSpan   │
│ NdkCrash     │  │ user attrs)  │  │  Processor,  │
│ JankTracker  │  │              │  │  OTLP/HTTP)  │
│ LifecycleTracker              │  │              │
│ NavigationTracker             │  │              │
│ StartupTracer │  │              │  │              │
│ NetworkInterceptor            │  │              │
└──────────────┘  └──────────────┘  └──────────────┘
```

All collectors emit to a shared OTel SDK instance. SessionManager attaches resource attributes (session ID, device info, user attributes) to all telemetry.

---

## Features

### 1. JVM Crash Capture (`CrashHandler.kt`)

- Sets `Thread.setDefaultUncaughtExceptionHandler` at SDK init
- Captures exception class, message, stacktrace, thread name
- Emits OTel Log with severity ERROR synchronously (before process dies)
- Chains to any existing handler after capturing

### 2. Coroutine Exception Handling (`CrashHandler.kt`)

- Implements `CoroutineExceptionHandler` loaded via ServiceLoader
- `META-INF/services/kotlinx.coroutines.CoroutineExceptionHandler` points to handler
- Captures coroutine context info (job name, dispatcher) where available
- Emits OTel Log with severity ERROR

### 3. NDK Crash Capture (`NdkCrashHandler.kt` + `ndk_crash_handler.c`)

- Registers signal handlers: SIGSEGV, SIGABRT, SIGBUS, SIGFPE, SIGILL
- **Async-signal-safe only** — no malloc, printf, locks
- Pre-allocates buffers and pre-opens crash file FD at init
- On signal: writes signal type, fault address, registers, backtrace to file
- Custom stack-based itoa for integer conversion
- Re-raises signal after capture for normal crash flow
- On next cold start: JNI bridge reads crash file, emits OTel Log (severity FATAL), deletes file
- Raw addresses only — symbolication happens offline with `.so` files
- ~200-300 lines of C

### 4. ANR Detection (`AnrWatchdog.kt`)

**Mechanism**: Watchdog thread pattern
- Posts a no-op `Runnable` to main looper every 5 seconds
- If the Runnable hasn't executed within 5s threshold, ANR detected

**On ANR detection**:
1. Immediately capture main thread stacktrace first (the blocked thread)
2. Then capture all other thread stacktraces for context
3. Emit OTel Log with severity ERROR, include all stacks

**Guards**:
- Skip detection if `Debug.isDebuggerConnected()`
- On API 30+: check `ActivityManager.getHistoricalProcessExitReasons()` on cold start

### 5. Startup Timing (`StartupTracer.kt`)

**TTID (Time to Initial Display)**:
- Capture `Process.getStartElapsedRealtime()` at SDK init
- Register `ActivityLifecycleCallbacks`, detect first `onResume()`
- Emit `app.startup` span with duration

**TTFD (Time to Full Display)**:
- App calls `Telemetry.reportFullyDrawn()` when truly interactive
- Emit `app.startup.full` span with duration from process start

**Timing discipline**:
- `SystemClock.elapsedRealtime()` for durations (monotonic)
- `System.currentTimeMillis()` for OTel timestamps (wall clock)

### 6. Network Monitoring (`NetworkInterceptor.kt`)

**OkHttp Interceptor** emits a span per request with:
- `http.method`, `http.status_code`, duration
- `http.request.body.size`, `http.response.body.size`
- Error status on failure

**PII Protection**:
- **Header stripping**: Never capture `Authorization`, `Cookie`, `Set-Cookie`, `X-Api-Key`, or headers containing "token"/"auth"
- **URL sanitization**:
  - Replace path segments that look like IDs (UUIDs, numeric) with `{id}`
  - Strip query parameters by default
  - Configurable via `urlSanitizer` lambda in init

### 7. Frame Metrics (`JankTracker.kt`)

**Uses AndroidX JankStats** (`androidx.metrics:metrics-performance`)

**Local aggregation** — does NOT emit per-frame. Every 30 seconds emits OTel Metrics:
- `frames.total` — total frames rendered
- `frames.slow` — frames >16ms (missed vsync)
- `frames.frozen` — frames >700ms (UI freeze)

Current activity/screen name attached as attribute.

### 8. App Lifecycle (`LifecycleTracker.kt`)

**Uses `ProcessLifecycleOwner`** to track foreground/background transitions.

**Emits**:
- `app.foreground` event when app enters foreground
- `app.background` event when app enters background
- `session.duration` attribute on background transition

**API 34+ Considerations**:
- Handle "Predictive Back" gesture
- Flush pending telemetry asynchronously via `lifecycleScope` + `Dispatchers.IO`
- Accept potential telemetry loss if process killed mid-export

### 9. Screen View Tracking (`NavigationTracker.kt`)

**Compose Navigation integration** via `Telemetry.trackNavigation(navController)`:
- Observes `currentBackStackEntryFlow`
- On route change: emit `screen.view` span with `screen.name` = route
- End previous screen's span (captures time-on-screen)

**Cardinality control**: Parameterize route arguments — `podcast/{id}` not `podcast/12345`

### 10. Custom Instrumentation API (`Telemetry.kt`)

```kotlin
// Create custom spans
val span = Telemetry.startSpan("load_podcast_list")
// ... do work ...
span.end()

// Log discrete events
Telemetry.logEvent("playback_started", mapOf(
    "podcast_id" to "123",
    "duration_ms" to 3600000
))
```

### 11. User & Session Attributes (`Telemetry.kt`)

- `Telemetry.setUserId(id: String?)` — attaches to all subsequent telemetry
- `Telemetry.setAttribute(key: String, value: String)` — custom attributes

Attributes propagate to all spans and logs via resource attributes.

### 12. Session Management (`SessionManager.kt`)

**Session ID regenerated when**:
1. Cold start (new process)
2. App returns to foreground after 30+ minutes in background

**Implementation**:
- Persist `lastBackgroundTimestamp` to SharedPreferences
- On foreground: check if (now - lastBackground) > 30 minutes
- If stale, generate new UUID for `session.id`

**Resource attributes** attached to all telemetry:
- `session.id`, `device.model`, `device.manufacturer`
- `os.version` (API level), `app.version`, `app.version.code`

---

## OTel Configuration (`OtelConfig.kt`)

**Export Protocol**: OTLP/HTTP with JSON (not gRPC)

**SDK Setup**:
- `SdkTracerProvider` with `BatchSpanProcessor`
- `SdkLoggerProvider` for crash/ANR logs
- `SdkMeterProvider` for frame metrics
- Resource attributes populated from `SessionManager`

**Batching**:
- Max queue size: 200 spans
- Max batch size: 50
- Export interval: 30 seconds
- On queue full: drop oldest — never OOM

---

## Threading & Resiliency

**Threading**:
- Dedicated single-thread dispatcher for all telemetry work
- `Telemetry.init()` returns immediately; setup continues async
- Never blocks main thread during `Application.onCreate()`

**Export Failures**:
- Check `ConnectivityManager` before attempting export
- If offline: silently drop batch (no persistent disk queue)
- On failure: exponential backoff, max 3 retries, then drop

**Safety**:
- All SDK code wrapped in try/catch
- Internal errors logged but never surface to host app

---

## Project Structure

```
telemetry/
├── src/main/kotlin/
│   └── com/simplecityapps/telemetry/android/
│       ├── Telemetry.kt              # Public API, singleton
│       ├── CrashHandler.kt           # JVM + coroutine crashes
│       ├── NdkCrashHandler.kt        # JNI bridge for native crashes
│       ├── AnrWatchdog.kt            # ANR detection
│       ├── StartupTracer.kt          # TTID/TTFD
│       ├── NetworkInterceptor.kt     # OkHttp spans, URL sanitization
│       ├── JankTracker.kt            # JankStats integration
│       ├── LifecycleTracker.kt       # Foreground/background
│       ├── NavigationTracker.kt      # Compose nav screen views
│       ├── SessionManager.kt         # Session ID, 30-min timeout
│       └── OtelConfig.kt             # SDK/exporter setup, threading
├── src/main/cpp/
│   ├── ndk_crash_handler.c           # Signal handlers
│   ├── ndk_crash_handler.h
│   └── CMakeLists.txt
├── src/main/resources/
│   └── META-INF/services/
│       └── kotlinx.coroutines.CoroutineExceptionHandler
├── consumer-rules.pro
└── build.gradle.kts
```

---

## Dependencies

```kotlin
dependencies {
    implementation("io.opentelemetry:opentelemetry-api:1.32.0")
    implementation("io.opentelemetry:opentelemetry-sdk:1.32.0")
    implementation("io.opentelemetry:opentelemetry-exporter-otlp:1.32.0")
    implementation("androidx.metrics:metrics-performance:1.0.0")
    implementation("androidx.lifecycle:lifecycle-process:2.7.0")

    // Likely already in app
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("androidx.navigation:navigation-compose:2.7.6")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
```

**Do NOT include**: gRPC exporter, full OTel Android SDK, Embrace/Sentry/Firebase/Crashlytics.

---

## Usage

```kotlin
// Application.onCreate()
Telemetry.init(
    context = this,
    endpoint = "https://otel.mydomain.com",
    serviceName = "my-android-app",
    urlSanitizer = { url ->
        url.replace(Regex("/users/[^/]+"), "/users/{id}")
    }
)

// When app is truly usable
Telemetry.reportFullyDrawn()

// Compose Navigation
LaunchedEffect(navController) {
    Telemetry.trackNavigation(navController)
}

// OkHttp
val client = OkHttpClient.Builder()
    .addInterceptor(Telemetry.okHttpInterceptor())
    .build()

// Custom spans
val span = Telemetry.startSpan("load_podcast_list")
span.end()

// Events
Telemetry.logEvent("playback_started", mapOf("podcast_id" to "123"))

// User identification
Telemetry.setUserId("user_abc")
Telemetry.setAttribute("subscription", "premium")
```

---

## What NOT to Build

- Bytecode instrumentation or Gradle plugin
- HttpURLConnection monitoring
- Persistent disk-backed offline buffer
- Compose recomposition tracking
- Battery/thermal monitoring
- Symbol upload pipeline

---

## Target Size

~1200-1800 lines total (including ~200-300 lines of C for NDK handler)
