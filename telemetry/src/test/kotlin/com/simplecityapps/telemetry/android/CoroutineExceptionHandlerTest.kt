package com.simplecityapps.telemetry.android

import com.simplecityapps.telemetry.android.fakes.InMemoryTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.logs.Severity
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Job
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.coroutines.CoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CoroutineExceptionHandlerTest {

    private lateinit var telemetry: InMemoryTelemetry

    @Before
    fun setup() {
        telemetry = InMemoryTelemetry()
    }

    @After
    fun teardown() {
        telemetry.shutdown()
    }

    @Test
    fun `captures coroutine exception with ERROR severity`() {
        val handler = TelemetryCoroutineExceptionHandler()
        handler.setLogger(telemetry.logger)

        val exception = IllegalStateException("Coroutine failed")
        val context: CoroutineContext = Job() + CoroutineName("test-coroutine")

        handler.handleException(context, exception)

        val logs = telemetry.getLogRecords()
        assertEquals(1, logs.size)
        assertEquals(Severity.ERROR, logs[0].severity)
    }

    @Test
    fun `includes coroutine name in attributes`() {
        val handler = TelemetryCoroutineExceptionHandler()
        handler.setLogger(telemetry.logger)

        val exception = RuntimeException("Test")
        val context: CoroutineContext = Job() + CoroutineName("my-coroutine")

        handler.handleException(context, exception)

        val logs = telemetry.getLogRecords()
        val coroutineName = logs[0].attributes.get(AttributeKey.stringKey("coroutine.name"))
        assertEquals("my-coroutine", coroutineName)
    }

    @Test
    fun `includes exception details in log body`() {
        val handler = TelemetryCoroutineExceptionHandler()
        handler.setLogger(telemetry.logger)

        val exception = RuntimeException("Something went wrong")
        val context: CoroutineContext = Job()

        handler.handleException(context, exception)

        val logs = telemetry.getLogRecords()
        assertTrue(logs[0].body.asString().contains("RuntimeException"))
    }

    @Test
    fun `handles missing coroutine name gracefully`() {
        val handler = TelemetryCoroutineExceptionHandler()
        handler.setLogger(telemetry.logger)

        val exception = RuntimeException("Test")
        val context: CoroutineContext = Job() // No CoroutineName

        handler.handleException(context, exception)

        val logs = telemetry.getLogRecords()
        assertEquals(1, logs.size)
    }

    @Test
    fun `does not emit log if logger not set`() {
        val handler = TelemetryCoroutineExceptionHandler()
        // Don't set logger

        val exception = RuntimeException("Test")
        val context: CoroutineContext = Job()

        handler.handleException(context, exception)

        val logs = telemetry.getLogRecords()
        assertEquals(0, logs.size)
    }
}
