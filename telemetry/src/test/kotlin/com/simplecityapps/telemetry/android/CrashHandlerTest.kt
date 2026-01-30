package com.simplecityapps.telemetry.android

import com.simplecityapps.telemetry.android.fakes.InMemoryTelemetry
import io.opentelemetry.api.logs.Severity
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CrashHandlerTest {

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
    fun `captures exception and emits log with ERROR severity`() {
        var chainedHandlerCalled = false
        val previousHandler = Thread.UncaughtExceptionHandler { _, _ ->
            chainedHandlerCalled = true
        }

        val handler = CrashHandler(telemetry.logger, previousHandler)
        val exception = RuntimeException("Test crash")

        handler.uncaughtException(Thread.currentThread(), exception)

        val logs = telemetry.getLogRecords()
        assertEquals(1, logs.size)
        assertEquals(Severity.ERROR, logs[0].severity)
        assertTrue(logs[0].body.asString().contains("RuntimeException"))
        assertTrue(chainedHandlerCalled, "Should chain to previous handler")
    }

    @Test
    fun `includes thread name in log attributes`() {
        val handler = CrashHandler(telemetry.logger, null)
        val exception = RuntimeException("Test crash")
        val thread = Thread("test-thread-name")

        handler.uncaughtException(thread, exception)

        val logs = telemetry.getLogRecords()
        assertEquals(1, logs.size)

        val threadName = logs[0].attributes.get(io.opentelemetry.api.common.AttributeKey.stringKey("thread.name"))
        assertEquals("test-thread-name", threadName)
    }

    @Test
    fun `includes exception stacktrace in log attributes`() {
        val handler = CrashHandler(telemetry.logger, null)
        val exception = RuntimeException("Test crash")

        handler.uncaughtException(Thread.currentThread(), exception)

        val logs = telemetry.getLogRecords()
        val stacktrace = logs[0].attributes.get(io.opentelemetry.api.common.AttributeKey.stringKey("exception.stacktrace"))
        assertTrue(stacktrace?.contains("RuntimeException") == true)
        assertTrue(stacktrace?.contains("Test crash") == true)
    }

    @Test
    fun `handles null previous handler gracefully`() {
        val handler = CrashHandler(telemetry.logger, null)
        val exception = RuntimeException("Test crash")

        handler.uncaughtException(Thread.currentThread(), exception)

        val logs = telemetry.getLogRecords()
        assertEquals(1, logs.size)
    }
}
