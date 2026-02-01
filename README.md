# App Health Android SDK

Automatic crash reporting and ANR detection for Android apps, built on OpenTelemetry.

## Quick Start

```kotlin
// In Application.onCreate()
val openTelemetry = createOpenTelemetrySdk("https://collector.example.com", "my-app")
AppHealth.init(context = this, openTelemetry = openTelemetry)
```

That's it. The SDK automatically captures crashes and ANRs.

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

## Configuration

All features are **enabled by default**. Disable what you don't need:

```kotlin
AppHealth.init(context, openTelemetry) {
    crashHandling = true          // JVM crash handler (default: true)
    coroutineCrashHandling = true // Coroutine exceptions (default: true)
    anrDetection = true           // ANR watchdog (default: true)
    ndkCrashHandling = true       // Native crashes (default: true)
}
```

## Additional Telemetry

For network tracing, navigation tracking, lifecycle events, startup timing, and frame metrics, see [docs/RECIPES.md](docs/RECIPES.md) for implementation patterns using OpenTelemetry directly.

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
