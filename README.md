# App Health Android SDK

Automatic crash reporting, performance metrics, and observability for Android apps, built on OpenTelemetry.

## Quick Start

```kotlin
// In Application.onCreate()
AppHealth.init(
    context = this,
    endpoint = "https://collector.example.com",
    serviceName = "my-app"
)
```

That's it. The SDK automatically captures crashes, ANRs, startup timing, jank, and lifecycle events.

## What's Collected

| Feature | Description |
|---------|-------------|
| **Crashes** | JVM exceptions, coroutine failures, native (NDK) crashes |
| **ANRs** | Main thread blocked > 5 seconds |
| **Startup** | Time to Initial/Full Display (TTID/TTFD) |
| **Jank** | Slow and frozen frames via FrameMetrics |
| **Lifecycle** | Foreground/background transitions, session duration |

## Configuration

All features are **enabled by default**. Disable what you don't need:

```kotlin
AppHealth.init(context, endpoint, serviceName) {
    crashHandling = true          // JVM crash handler (default: true)
    coroutineCrashHandling = true // Coroutine exceptions (default: true)
    anrDetection = true           // ANR watchdog (default: true)
    ndkCrashHandling = true       // Native crashes (default: true)
    lifecycleTracking = true      // Foreground/background (default: true)
    startupTracking = true        // TTID/TTFD (default: true)
    jankTracking = true           // Frame metrics (default: true)
    installationIdEnabled = true  // Device correlation ID (default: true)
}
```

## Optional Integrations

### Network Tracing

Add the interceptor to your OkHttp client:

```kotlin
val client = OkHttpClient.Builder()
    .addInterceptor(AppHealth.okHttpInterceptor())
    .build()
```

URLs are automatically sanitized (IDs, UUIDs, query params stripped).

**Sampling** — For high-traffic apps, reduce telemetry volume:

```kotlin
AppHealth.init(context, endpoint, serviceName) {
    networkSampling {
        successSampleRate = 0.1   // Sample 10% of successful requests
        maxErrorsPerMinute = 10   // Cap errors during outages (default: 10)
    }
}
```

**Custom URL Sanitizer** — Override the default ID/UUID stripping:

```kotlin
AppHealth.init(context, endpoint, serviceName) {
    urlSanitizer = { url ->
        url.replace(Regex("/v[0-9]+/"), "/v{version}/")
    }
}
```

### Navigation Tracking (Jetpack Compose)

```kotlin
val navController = rememberNavController()
LaunchedEffect(navController) {
    AppHealth.trackNavigation(navController)
}
```

### Startup Timing

TTID is automatic. For TTFD, call when your main content is ready:

```kotlin
// In your main activity, after critical content loads
AppHealth.reportFullyDrawn()
```

## Sharing the OTel Instance

Use the same OpenTelemetry instance for your own instrumentation:

```kotlin
AppHealth.init(context, endpoint, serviceName)
GlobalOpenTelemetry.set(AppHealth.openTelemetry)

// Your telemetry now shares session.id, device info, etc.
val tracer = GlobalOpenTelemetry.get().getTracer("my-feature")
val span = tracer.spanBuilder("my-operation").startSpan()
```

## Initialization Control

```kotlin
AppHealth.init(context, endpoint, serviceName)

// Check if init() was called
if (AppHealth.isInitialized) { ... }

// Check if async init completed
if (AppHealth.isReady) { ... }

// Block until ready (useful in tests)
AppHealth.awaitReady(timeoutMs = 5000)

// Force flush telemetry (useful in tests)
AppHealth.forceFlush()
```

## Privacy & GDPR

The SDK collects only technical data required for crash reporting and performance monitoring. No personal data is collected.

### Data Collected

| Attribute | Purpose | Persistence |
|-----------|---------|-------------|
| `session.id` | Correlate events within a session | Rotates after 30 min inactivity |
| `device.installation.id` | Correlate telemetry from same device | Persists until app uninstall |
| `device.model.name` | Device context for debugging | N/A |
| `device.manufacturer` | Device context for debugging | N/A |
| `os.version` | OS context for debugging | N/A |
| `app.version` | App version for debugging | N/A |

### Installation ID

The installation ID is a **random UUID** that allows you to distinguish "100 crashes from 1 device" vs "100 crashes from 100 devices". It is:

- **Not derived from hardware** — No IMEI, SSAID, MAC address, or Advertising ID
- **App-scoped** — Stored in app-private SharedPreferences, not shared across apps
- **Deleted on uninstall** — No persistent tracking
- **Opt-out available** — Disable with `installationIdEnabled = false`

### GDPR Compliance

To disable the installation ID for GDPR compliance:

```kotlin
AppHealth.init(context, endpoint, serviceName) {
    installationIdEnabled = false
}
```

When disabled, the `device.installation.id` attribute is omitted from all telemetry.

## Requirements

- Android 8.0+ (API 26)
- Kotlin 1.7+

## License

```
Copyright 2026 Simple City Apps

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```
