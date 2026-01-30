package com.simplecityapps.apphealth.sample

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
import java.util.concurrent.TimeUnit

/**
 * Helper object for creating OpenTelemetry SDK instances in E2E tests.
 */
object E2ETestOpenTelemetry {

    /**
     * Creates an OpenTelemetrySdk configured for E2E testing.
     * Uses short delays to speed up test execution.
     */
    fun create(endpoint: String, serviceName: String): OpenTelemetrySdk {
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
                        .setTimeout(Duration.ofSeconds(5))
                        .build()
                )
                    .setMaxQueueSize(100)
                    .setMaxExportBatchSize(20)
                    .setScheduleDelay(Duration.ofMillis(100)) // Fast for tests
                    .build()
            )
            .build()

        val loggerProvider = SdkLoggerProvider.builder()
            .setResource(resource)
            .addLogRecordProcessor(
                BatchLogRecordProcessor.builder(
                    OtlpHttpLogRecordExporter.builder()
                        .setEndpoint("$endpoint/v1/logs")
                        .setTimeout(Duration.ofSeconds(5))
                        .build()
                )
                    .setMaxQueueSize(100)
                    .setMaxExportBatchSize(20)
                    .setScheduleDelay(Duration.ofMillis(100)) // Fast for tests
                    .build()
            )
            .build()

        val meterProvider = SdkMeterProvider.builder()
            .setResource(resource)
            .registerMetricReader(
                PeriodicMetricReader.builder(
                    OtlpHttpMetricExporter.builder()
                        .setEndpoint("$endpoint/v1/metrics")
                        .setTimeout(Duration.ofSeconds(5))
                        .build()
                )
                    .setInterval(Duration.ofMillis(500)) // Fast for tests
                    .build()
            )
            .build()

        return OpenTelemetrySdk.builder()
            .setTracerProvider(tracerProvider)
            .setLoggerProvider(loggerProvider)
            .setMeterProvider(meterProvider)
            .build()
    }

    /**
     * Shuts down the SDK, waiting for flush to complete.
     */
    fun shutdown(sdk: OpenTelemetrySdk) {
        runCatching {
            sdk.shutdown().join(5, TimeUnit.SECONDS)
        }
    }
}
