# OpenTelemetry SDK - Keep public API and resource detection
-keep class io.opentelemetry.api.** { *; }
-keep class io.opentelemetry.context.** { *; }
-keep class io.opentelemetry.sdk.resources.Resource { *; }
-keep class io.opentelemetry.sdk.common.** { *; }

# Keep OTLP exporters
-keep class io.opentelemetry.exporter.otlp.** { *; }

# Keep SDK trace/logs/metrics providers (used via reflection)
-keep class io.opentelemetry.sdk.trace.SdkTracerProvider { *; }
-keep class io.opentelemetry.sdk.logs.SdkLoggerProvider { *; }
-keep class io.opentelemetry.sdk.metrics.SdkMeterProvider { *; }

# Suppress warnings for optional dependencies
-dontwarn io.opentelemetry.**

# AppHealth Public API
-keep public class com.simplecityapps.apphealth.android.AppHealth {
    public *;
}
-keep public class com.simplecityapps.apphealth.android.AppHealthConfig {
    public *;
}

# JNI - Keep native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep ServiceLoader implementations
-keepnames class * implements io.opentelemetry.sdk.autoconfigure.spi.ConfigurablePropagatorProvider
-keepnames class * implements io.opentelemetry.sdk.autoconfigure.spi.traces.ConfigurableSpanExporterProvider
-keepnames class * implements io.opentelemetry.sdk.autoconfigure.spi.metrics.ConfigurableMetricExporterProvider
-keepnames class * implements io.opentelemetry.sdk.autoconfigure.spi.logs.ConfigurableLogRecordExporterProvider
