# OpenTelemetry - Keep all OTel classes for proper SDK functionality
-keep class io.opentelemetry.** { *; }
-keepclassmembers class io.opentelemetry.** { *; }
-dontwarn io.opentelemetry.**

# Telemetry Public API - Keep all public classes in the SDK
-keep public class com.simplecityapps.telemetry.android.** { public *; }
-keep public interface com.simplecityapps.telemetry.android.** { *; }

# JNI - Keep native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# ServiceLoader - Keep service implementations
-keepnames class * implements io.opentelemetry.sdk.autoconfigure.spi.ConfigurablePropagatorProvider
-keepnames class * implements io.opentelemetry.sdk.autoconfigure.spi.traces.ConfigurableSpanExporterProvider
-keepnames class * implements io.opentelemetry.sdk.autoconfigure.spi.metrics.ConfigurableMetricExporterProvider
-keepnames class * implements io.opentelemetry.sdk.autoconfigure.spi.logs.ConfigurableLogRecordExporterProvider

# Keep ServiceLoader mechanism
-keep class java.util.ServiceLoader { *; }
-keepclassmembers class * implements java.util.ServiceLoader$Provider {
    public <init>();
}
