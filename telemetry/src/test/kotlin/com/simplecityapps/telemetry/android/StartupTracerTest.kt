package com.simplecityapps.telemetry.android

import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanBuilder
import io.opentelemetry.api.trace.Tracer
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*
import kotlin.test.assertTrue

class StartupTracerTest {

    private lateinit var tracer: Tracer
    private lateinit var spanBuilder: SpanBuilder
    private lateinit var span: Span

    @Before
    fun setup() {
        span = mock {
            on { end() } doAnswer {}
            on { setAttribute(any<String>(), any<Long>()) } doReturn it
            on { setAttribute(any<String>(), any<String>()) } doReturn it
            on { setAttribute(any<String>(), any<Boolean>()) } doReturn it
        }
        spanBuilder = mock {
            on { startSpan() } doReturn span
            on { setAttribute(any<String>(), any<Long>()) } doReturn it
            on { setAttribute(any<String>(), any<String>()) } doReturn it
            on { setAttribute(any<String>(), any<Boolean>()) } doReturn it
        }
        tracer = mock {
            on { spanBuilder(any()) } doReturn spanBuilder
        }
    }

    @Test
    fun `creates startup span on first activity resume`() {
        val startupTracer = StartupTracer(tracer, processStartTime = 1000L)

        startupTracer.onFirstActivityResumed(currentTime = 1500L)

        verify(tracer).spanBuilder("app.startup")
        verify(span).end()
    }

    @Test
    fun `only records first activity resume`() {
        val startupTracer = StartupTracer(tracer, processStartTime = 1000L)

        startupTracer.onFirstActivityResumed(currentTime = 1500L)
        startupTracer.onFirstActivityResumed(currentTime = 2000L)

        verify(tracer, times(1)).spanBuilder("app.startup")
    }

    @Test
    fun `reportFullyDrawn creates full startup span`() {
        val startupTracer = StartupTracer(tracer, processStartTime = 1000L)

        startupTracer.reportFullyDrawn(currentTime = 2000L)

        verify(tracer).spanBuilder("app.startup.full")
        verify(span).end()
    }

    @Test
    fun `reportFullyDrawn only records once`() {
        val startupTracer = StartupTracer(tracer, processStartTime = 1000L)

        startupTracer.reportFullyDrawn(currentTime = 2000L)
        startupTracer.reportFullyDrawn(currentTime = 3000L)

        verify(tracer, times(1)).spanBuilder("app.startup.full")
    }
}
