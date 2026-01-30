package com.simplecityapps.apphealth.android.fakes

import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.logs.Logger
import io.opentelemetry.api.metrics.Meter
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.logs.SdkLoggerProvider
import io.opentelemetry.sdk.logs.data.LogRecordData
import io.opentelemetry.sdk.logs.export.SimpleLogRecordProcessor
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.testing.exporter.InMemoryLogRecordExporter
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import io.opentelemetry.sdk.trace.data.SpanData

/**
 * In-memory OpenTelemetry setup for testing.
 * Captures all emitted logs and spans for assertion.
 *
 * Provides an [OpenTelemetry] instance that can be passed to [AppHealth.init].
 */
class InMemoryTelemetry {

    private val logExporter = InMemoryLogRecordExporter.create()
    private val spanExporter = InMemorySpanExporter.create()

    private val loggerProvider = SdkLoggerProvider.builder()
        .addLogRecordProcessor(SimpleLogRecordProcessor.create(logExporter))
        .build()

    private val tracerProvider = SdkTracerProvider.builder()
        .addSpanProcessor(SimpleSpanProcessor.create(spanExporter))
        .build()

    private val meterProvider = SdkMeterProvider.builder()
        .build()

    /**
     * The OpenTelemetry SDK instance. Pass this to AppHealth.init().
     */
    val openTelemetry: OpenTelemetry = OpenTelemetrySdk.builder()
        .setTracerProvider(tracerProvider)
        .setLoggerProvider(loggerProvider)
        .setMeterProvider(meterProvider)
        .build()

    val logger: Logger = loggerProvider.get("test")
    val tracer: Tracer = tracerProvider.get("test")
    val meter: Meter = meterProvider.get("test")

    /**
     * Returns all captured log records.
     */
    fun getLogRecords(): List<LogRecordData> = logExporter.finishedLogRecordItems

    /**
     * Returns all captured spans.
     */
    fun getSpans(): List<SpanData> = spanExporter.finishedSpanItems

    /**
     * Clears all captured telemetry.
     */
    fun reset() {
        logExporter.reset()
        spanExporter.reset()
    }

    /**
     * Shuts down the providers.
     */
    fun shutdown() {
        loggerProvider.shutdown()
        tracerProvider.shutdown()
        meterProvider.shutdown()
    }
}
