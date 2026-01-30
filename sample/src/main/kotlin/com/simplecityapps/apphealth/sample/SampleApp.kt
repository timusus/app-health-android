package com.simplecityapps.apphealth.sample

import android.app.Application
import com.simplecityapps.apphealth.android.AppHealth
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.exporter.otlp.http.logs.OtlpHttpLogRecordExporter
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporter
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.logs.SdkLoggerProvider
import io.opentelemetry.sdk.logs.export.BatchLogRecordProcessor
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor
import java.time.Duration

class SampleApp : Application() {

    override fun onCreate() {
        super.onCreate()

        val endpoint = System.getProperty("apphealth.endpoint") ?: "http://10.0.2.2:4318"

        // Build OpenTelemetry SDK with app-specific configuration
        val otelSdk = createOpenTelemetrySdk(endpoint, "apphealth-sample")

        // Initialize AppHealth with the SDK
        AppHealth.init(
            context = this,
            openTelemetry = otelSdk
        )
    }

    private fun createOpenTelemetrySdk(endpoint: String, serviceName: String): OpenTelemetrySdk {
        val resource = Resource.create(
            Attributes.of(
                AttributeKey.stringKey("service.name"), serviceName,
                AttributeKey.stringKey("telemetry.sdk.name"), "opentelemetry",
                AttributeKey.stringKey("telemetry.sdk.language"), "kotlin"
            )
        )

        val tracerProvider = SdkTracerProvider.builder()
            .setResource(resource)
            .addSpanProcessor(
                BatchSpanProcessor.builder(
                    OtlpHttpSpanExporter.builder()
                        .setEndpoint("$endpoint/v1/traces")
                        .setTimeout(Duration.ofSeconds(10))
                        .build()
                )
                    .setMaxQueueSize(200)
                    .setMaxExportBatchSize(50)
                    .setScheduleDelay(Duration.ofSeconds(30))
                    .build()
            )
            .build()

        val loggerProvider = SdkLoggerProvider.builder()
            .setResource(resource)
            .addLogRecordProcessor(
                BatchLogRecordProcessor.builder(
                    OtlpHttpLogRecordExporter.builder()
                        .setEndpoint("$endpoint/v1/logs")
                        .setTimeout(Duration.ofSeconds(10))
                        .build()
                )
                    .setMaxQueueSize(200)
                    .setMaxExportBatchSize(50)
                    .setScheduleDelay(Duration.ofSeconds(30))
                    .build()
            )
            .build()

        val meterProvider = SdkMeterProvider.builder()
            .setResource(resource)
            .registerMetricReader(
                PeriodicMetricReader.builder(
                    OtlpHttpMetricExporter.builder()
                        .setEndpoint("$endpoint/v1/metrics")
                        .setTimeout(Duration.ofSeconds(10))
                        .build()
                )
                    .setInterval(Duration.ofSeconds(30))
                    .build()
            )
            .build()

        return OpenTelemetrySdk.builder()
            .setTracerProvider(tracerProvider)
            .setLoggerProvider(loggerProvider)
            .setMeterProvider(meterProvider)
            .build()
    }
}
