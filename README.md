# App Health Android SDK

Automatic crash reporting, performance metrics, and observability for Android apps, built on OpenTelemetry.

## Quick Start

```kotlin
// In Application.onCreate()
val openTelemetry = createOpenTelemetrySdk("https://collector.example.com", "my-app")
AppHealth.init(context = this, openTelemetry = openTelemetry)
```

That's it. The SDK automatically captures crashes, ANRs, startup timing, jank, and lifecycle events.

<details>
<summary><b>Full SDK setup example</b></summary>

```kotlin
fun createOpenTelemetrySdk(endpoint: String, serviceName: String): OpenTelemetrySdk {
    val resource = Resource.create(Attributes.of(
        AttributeKey.stringKey("service.name"), serviceName
    ))

    return OpenTelemetrySdk.builder()
        .setTracerProvider(SdkTracerProvider.builder()
            .setResource(resource)
            .addSpanProcessor(BatchSpanProcessor.builder(
                OtlpHttpSpanExporter.builder()
                    .setEndpoint("$endpoint/v1/traces")
                    .build()
            ).build())
            .build())
        .setLoggerProvider(SdkLoggerProvider.builder()
            .setResource(resource)
            .addLogRecordProcessor(BatchLogRecordProcessor.builder(
                OtlpHttpLogRecordExporter.builder()
                    .setEndpoint("$endpoint/v1/logs")
                    .build()
            ).build())
            .build())
        .build()
}
```
</details>

## What's Collected

| Feature | Description |
|---------|-------------|
| **Crashes** | JVM exceptions, coroutine failures, native (NDK) crashes |
| **ANRs** | Main thread blocked > 5 seconds |
| **Startup** | Time to Initial/Full Display (TTID/TTFD) |
| **Jank** | Slow and frozen frames via FrameMetrics |
| **Lifecycle** | Foreground/background transitions |

## Configuration

All features are **enabled by default**. Disable what you don't need:

```kotlin
AppHealth.init(context, openTelemetry) {
    crashHandling = true          // JVM crash handler (default: true)
    coroutineCrashHandling = true // Coroutine exceptions (default: true)
    anrDetection = true           // ANR watchdog (default: true)
    ndkCrashHandling = true       // Native crashes (default: true)
    lifecycleTracking = true      // Foreground/background (default: true)
    startupTracking = true        // TTID/TTFD (default: true)
    jankTracking = true           // Frame metrics (default: true)
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

**Distributed Tracing** — Trace context is automatically propagated to backend services via W3C `traceparent` headers, enabling end-to-end traces from mobile to backend. Configure propagators on your OpenTelemetry SDK:

```kotlin
val openTelemetry = OpenTelemetrySdk.builder()
    .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
    // ... other config
    .build()
```

**Network Config** — Configure sampling and trace propagation:

```kotlin
AppHealth.init(context, openTelemetry) {
    networkConfig {
        sampleRate = 0.1              // Sample 10% of requests (default: 1.0)
        traceContextPropagation = true // Inject traceparent headers (default: true)
    }
}
```

**Custom URL Sanitizer** — Override the default ID/UUID stripping:

```kotlin
AppHealth.init(context, openTelemetry) {
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

## Custom Instrumentation

For custom spans and logs, use the OpenTelemetry SDK you provided to AppHealth. See the [OpenTelemetry documentation](https://opentelemetry.io/docs/languages/java/).

## Initialization Control

```kotlin
AppHealth.init(context, openTelemetry)

// Check if init() was called
if (AppHealth.isInitialized) { ... }

// Check if async init completed
if (AppHealth.isReady) { ... }

// Block until ready (useful in tests)
AppHealth.awaitReady(timeoutMs = 5000)

// Force flush telemetry (useful in tests)
AppHealth.forceFlush()
```

## Privacy

The SDK collects only technical data required for crash reporting and performance monitoring. No personal data is collected. No device identifiers are generated or stored.

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
