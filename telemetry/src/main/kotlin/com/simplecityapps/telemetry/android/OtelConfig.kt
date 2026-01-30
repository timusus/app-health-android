package com.simplecityapps.telemetry.android

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.logs.Logger
import io.opentelemetry.api.metrics.Meter
import io.opentelemetry.api.trace.Tracer
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

internal class OtelConfig(
    endpoint: String,
    serviceName: String,
    sessionId: String,
    deviceModel: String,
    deviceManufacturer: String,
    osVersion: String,
    appVersion: String,
    appVersionCode: Long
) {
    private val resource: Resource = Resource.create(
        Attributes.builder()
            .put(AttributeKey.stringKey("service.name"), serviceName)
            .put(AttributeKey.stringKey("session.id"), sessionId)
            .put(AttributeKey.stringKey("device.model"), deviceModel)
            .put(AttributeKey.stringKey("device.manufacturer"), deviceManufacturer)
            .put(AttributeKey.stringKey("os.version"), osVersion)
            .put(AttributeKey.stringKey("app.version"), appVersion)
            .put(AttributeKey.longKey("app.version.code"), appVersionCode)
            .build()
    )

    private val spanExporter = OtlpHttpSpanExporter.builder()
        .setEndpoint("$endpoint/v1/traces")
        .setTimeout(Duration.ofSeconds(10))
        .build()

    private val logExporter = OtlpHttpLogRecordExporter.builder()
        .setEndpoint("$endpoint/v1/logs")
        .setTimeout(Duration.ofSeconds(10))
        .build()

    private val metricExporter = OtlpHttpMetricExporter.builder()
        .setEndpoint("$endpoint/v1/metrics")
        .setTimeout(Duration.ofSeconds(10))
        .build()

    private val tracerProvider = SdkTracerProvider.builder()
        .setResource(resource)
        .addSpanProcessor(
            BatchSpanProcessor.builder(spanExporter)
                .setMaxQueueSize(MAX_QUEUE_SIZE)
                .setMaxExportBatchSize(MAX_BATCH_SIZE)
                .setScheduleDelay(Duration.ofSeconds(EXPORT_INTERVAL_SECONDS))
                .build()
        )
        .build()

    private val loggerProvider = SdkLoggerProvider.builder()
        .setResource(resource)
        .addLogRecordProcessor(
            BatchLogRecordProcessor.builder(logExporter)
                .setMaxQueueSize(MAX_QUEUE_SIZE)
                .setMaxExportBatchSize(MAX_BATCH_SIZE)
                .setScheduleDelay(Duration.ofSeconds(EXPORT_INTERVAL_SECONDS))
                .build()
        )
        .build()

    private val meterProvider = SdkMeterProvider.builder()
        .setResource(resource)
        .registerMetricReader(
            PeriodicMetricReader.builder(metricExporter)
                .setInterval(Duration.ofSeconds(EXPORT_INTERVAL_SECONDS))
                .build()
        )
        .build()

    private val sdk = OpenTelemetrySdk.builder()
        .setTracerProvider(tracerProvider)
        .setLoggerProvider(loggerProvider)
        .setMeterProvider(meterProvider)
        .build()

    val tracer: Tracer = sdk.getTracer(INSTRUMENTATION_NAME, INSTRUMENTATION_VERSION)

    val logger: Logger = sdk.logsBridge.loggerBuilder(INSTRUMENTATION_NAME).build()

    val meter: Meter = sdk.getMeter(INSTRUMENTATION_NAME)

    fun shutdown() {
        runCatching { sdk.shutdown().join(5, TimeUnit.SECONDS) }
    }

    fun forceFlush() {
        runCatching {
            tracerProvider.forceFlush().join(2, TimeUnit.SECONDS)
            loggerProvider.forceFlush().join(2, TimeUnit.SECONDS)
        }
    }

    companion object {
        private const val INSTRUMENTATION_NAME = "com.simplecityapps.telemetry.android"
        private const val INSTRUMENTATION_VERSION = "1.0.0"
        private const val MAX_QUEUE_SIZE = 200
        private const val MAX_BATCH_SIZE = 50
        private const val EXPORT_INTERVAL_SECONDS = 30L
    }
}
