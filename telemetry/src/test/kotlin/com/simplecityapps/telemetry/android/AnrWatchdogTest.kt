package com.simplecityapps.telemetry.android

import com.simplecityapps.telemetry.android.fakes.InMemoryTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.logs.Severity
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AnrWatchdogTest {

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
    fun `detects ANR when main thread blocked`() {
        val watchdog = AnrWatchdog(
            logger = telemetry.logger,
            timeoutMs = 100,
            checkIntervalMs = 50
        )

        val anrDetected = watchdog.checkForAnr(mainThreadResponded = false)

        assertTrue(anrDetected)
    }

    @Test
    fun `does not detect ANR when main thread responds`() {
        val watchdog = AnrWatchdog(
            logger = telemetry.logger,
            timeoutMs = 100,
            checkIntervalMs = 50
        )

        val anrDetected = watchdog.checkForAnr(mainThreadResponded = true)

        assertFalse(anrDetected)
    }

    @Test
    fun `emits log with ERROR severity on ANR`() {
        val watchdog = AnrWatchdog(
            logger = telemetry.logger,
            timeoutMs = 100,
            checkIntervalMs = 50
        )

        watchdog.reportAnr(Thread.currentThread().stackTrace)

        val logs = telemetry.getLogRecords()
        assertEquals(1, logs.size)
        assertEquals(Severity.ERROR, logs[0].severity)
        assertTrue(logs[0].body.asString().contains("ANR Detected"))
    }

    @Test
    fun `includes main thread stacktrace in ANR report`() {
        val watchdog = AnrWatchdog(
            logger = telemetry.logger,
            timeoutMs = 100,
            checkIntervalMs = 50
        )

        val stackTrace = Thread.currentThread().stackTrace
        watchdog.reportAnr(stackTrace)

        val logs = telemetry.getLogRecords()
        val stacktraceAttr = logs[0].attributes.get(AttributeKey.stringKey("main_thread.stacktrace"))
        assertNotNull(stacktraceAttr)
        assertTrue(stacktraceAttr.isNotEmpty())
    }

    @Test
    fun `includes ANR type in attributes`() {
        val watchdog = AnrWatchdog(
            logger = telemetry.logger,
            timeoutMs = 100,
            checkIntervalMs = 50
        )

        watchdog.reportAnr(Thread.currentThread().stackTrace)

        val logs = telemetry.getLogRecords()
        val anrType = logs[0].attributes.get(AttributeKey.stringKey("anr.type"))
        assertEquals("watchdog", anrType)
    }
}
