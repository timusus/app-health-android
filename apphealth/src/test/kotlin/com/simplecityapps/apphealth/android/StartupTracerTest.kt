package com.simplecityapps.apphealth.android

import android.app.Activity
import com.simplecityapps.apphealth.android.fakes.InMemoryTelemetry
import io.opentelemetry.api.common.AttributeKey
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StartupTracerTest {

    private lateinit var telemetry: InMemoryTelemetry
    private lateinit var activity: Activity

    @Before
    fun setup() {
        telemetry = InMemoryTelemetry()
        activity = mock()
    }

    @After
    fun teardown() {
        telemetry.shutdown()
    }

    @Test
    fun `creates startup span on first activity resume`() {
        val startupTracer = StartupTracer(
            tracer = telemetry.tracer,
            processStartTime = 1000L,
            clock = { 1500L }
        )

        startupTracer.onFirstActivityResumed(currentTime = 1500L)

        val spans = telemetry.getSpans()
        assertEquals(1, spans.size)
        assertEquals("app.startup", spans[0].name)
    }

    @Test
    fun `records startup duration in span attributes`() {
        val startupTracer = StartupTracer(
            tracer = telemetry.tracer,
            processStartTime = 1000L,
            clock = { 1500L }
        )

        startupTracer.onFirstActivityResumed(currentTime = 1500L)

        val spans = telemetry.getSpans()
        val duration = spans[0].attributes.get(AttributeKey.longKey("startup.duration_ms"))
        assertEquals(500L, duration) // 1500 - 1000
    }

    @Test
    fun `marks startup type as cold`() {
        val startupTracer = StartupTracer(
            tracer = telemetry.tracer,
            processStartTime = 1000L,
            clock = { 1500L }
        )

        startupTracer.onFirstActivityResumed(currentTime = 1500L)

        val spans = telemetry.getSpans()
        val startupType = spans[0].attributes.get(AttributeKey.stringKey("startup.type"))
        assertEquals("cold", startupType)
    }

    @Test
    fun `only records first activity resume via lifecycle callback`() {
        val startupTracer = StartupTracer(
            tracer = telemetry.tracer,
            processStartTime = 1000L,
            clock = { 1500L }
        )

        // Use the lifecycle callback which has the guard
        startupTracer.onActivityResumed(activity)
        startupTracer.onActivityResumed(activity)

        val spans = telemetry.getSpans()
        assertEquals(1, spans.size)
    }

    @Test
    fun `reportFullyDrawn creates full startup span`() {
        val startupTracer = StartupTracer(
            tracer = telemetry.tracer,
            processStartTime = 1000L,
            clock = { 2000L }
        )

        startupTracer.reportFullyDrawn(currentTime = 2000L)

        val spans = telemetry.getSpans()
        assertEquals(1, spans.size)
        assertEquals("app.startup.full", spans[0].name)
    }

    @Test
    fun `reportFullyDrawn includes fully_drawn attribute`() {
        val startupTracer = StartupTracer(
            tracer = telemetry.tracer,
            processStartTime = 1000L,
            clock = { 2000L }
        )

        startupTracer.reportFullyDrawn(currentTime = 2000L)

        val spans = telemetry.getSpans()
        val fullyDrawn = spans[0].attributes.get(AttributeKey.booleanKey("startup.fully_drawn"))
        assertTrue(fullyDrawn == true)
    }

    @Test
    fun `reportFullyDrawn only records once`() {
        val startupTracer = StartupTracer(
            tracer = telemetry.tracer,
            processStartTime = 1000L,
            clock = { 2000L }
        )

        startupTracer.reportFullyDrawn(currentTime = 2000L)
        startupTracer.reportFullyDrawn(currentTime = 3000L)

        val spans = telemetry.getSpans()
        assertEquals(1, spans.size)
    }
}
