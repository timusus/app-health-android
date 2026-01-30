package com.simplecityapps.apphealth.android

import com.simplecityapps.apphealth.android.fakes.InMemoryTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.logs.Severity
import io.opentelemetry.api.trace.SpanKind
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals

class SessionAwareTracerTest {

    private lateinit var telemetry: InMemoryTelemetry
    private var currentSessionId = "session-1"

    @Before
    fun setup() {
        telemetry = InMemoryTelemetry()
        currentSessionId = "session-1"
    }

    @After
    fun teardown() {
        telemetry.shutdown()
    }

    @Test
    fun `adds session id to span`() {
        val sessionAwareTracer = SessionAwareTracer(telemetry.tracer) { currentSessionId }

        val span = sessionAwareTracer.spanBuilder("test-span").startSpan()
        span.end()

        val spans = telemetry.getSpans()
        assertEquals(1, spans.size)
        assertEquals("session-1", spans[0].attributes.get(AttributeKey.stringKey("session.id")))
    }

    @Test
    fun `uses current session id at span start time`() {
        val sessionAwareTracer = SessionAwareTracer(telemetry.tracer) { currentSessionId }

        // Start with session-1
        val span1 = sessionAwareTracer.spanBuilder("span-1").startSpan()
        span1.end()

        // Change session ID
        currentSessionId = "session-2"

        // New span should use session-2
        val span2 = sessionAwareTracer.spanBuilder("span-2").startSpan()
        span2.end()

        val spans = telemetry.getSpans()
        assertEquals(2, spans.size)
        assertEquals("session-1", spans[0].attributes.get(AttributeKey.stringKey("session.id")))
        assertEquals("session-2", spans[1].attributes.get(AttributeKey.stringKey("session.id")))
    }

    @Test
    fun `preserves other span attributes`() {
        val sessionAwareTracer = SessionAwareTracer(telemetry.tracer) { currentSessionId }

        val span = sessionAwareTracer.spanBuilder("test-span")
            .setAttribute("custom.attr", "custom-value")
            .setAttribute("custom.number", 42L)
            .setSpanKind(SpanKind.CLIENT)
            .startSpan()
        span.end()

        val spans = telemetry.getSpans()
        assertEquals(1, spans.size)
        assertEquals("custom-value", spans[0].attributes.get(AttributeKey.stringKey("custom.attr")))
        assertEquals(42L, spans[0].attributes.get(AttributeKey.longKey("custom.number")))
        assertEquals(SpanKind.CLIENT, spans[0].kind)
        assertEquals("session-1", spans[0].attributes.get(AttributeKey.stringKey("session.id")))
    }
}

class SessionAwareLoggerTest {

    private lateinit var telemetry: InMemoryTelemetry
    private var currentSessionId = "session-1"

    @Before
    fun setup() {
        telemetry = InMemoryTelemetry()
        currentSessionId = "session-1"
    }

    @After
    fun teardown() {
        telemetry.shutdown()
    }

    @Test
    fun `adds session id to log record`() {
        val sessionAwareLogger = SessionAwareLogger(telemetry.logger) { currentSessionId }

        sessionAwareLogger.logRecordBuilder()
            .setBody("test log")
            .emit()

        val logs = telemetry.getLogRecords()
        assertEquals(1, logs.size)
        assertEquals("session-1", logs[0].attributes.get(AttributeKey.stringKey("session.id")))
    }

    @Test
    fun `uses current session id at emit time`() {
        val sessionAwareLogger = SessionAwareLogger(telemetry.logger) { currentSessionId }

        // Emit with session-1
        sessionAwareLogger.logRecordBuilder()
            .setBody("log-1")
            .emit()

        // Change session ID
        currentSessionId = "session-2"

        // New log should use session-2
        sessionAwareLogger.logRecordBuilder()
            .setBody("log-2")
            .emit()

        val logs = telemetry.getLogRecords()
        assertEquals(2, logs.size)
        assertEquals("session-1", logs[0].attributes.get(AttributeKey.stringKey("session.id")))
        assertEquals("session-2", logs[1].attributes.get(AttributeKey.stringKey("session.id")))
    }

    @Test
    fun `preserves other log attributes`() {
        val sessionAwareLogger = SessionAwareLogger(telemetry.logger) { currentSessionId }

        sessionAwareLogger.logRecordBuilder()
            .setBody("test log")
            .setSeverity(Severity.ERROR)
            .setAttribute(AttributeKey.stringKey("custom.attr"), "custom-value")
            .emit()

        val logs = telemetry.getLogRecords()
        assertEquals(1, logs.size)
        assertEquals("test log", logs[0].body.asString())
        assertEquals(Severity.ERROR, logs[0].severity)
        assertEquals("custom-value", logs[0].attributes.get(AttributeKey.stringKey("custom.attr")))
        assertEquals("session-1", logs[0].attributes.get(AttributeKey.stringKey("session.id")))
    }
}
