package com.simplecityapps.telemetry.android

import io.opentelemetry.api.logs.Logger
import io.opentelemetry.api.logs.LogRecordBuilder
import io.opentelemetry.api.logs.Severity
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*
import kotlin.test.assertTrue

class AnrWatchdogTest {

    private lateinit var logger: Logger
    private lateinit var logRecordBuilder: LogRecordBuilder

    @Before
    fun setup() {
        logRecordBuilder = mock {
            on { setSeverity(any()) } doReturn it
            on { setBody(any<String>()) } doReturn it
            on { setAllAttributes(any()) } doReturn it
            on { emit() } doAnswer {}
        }
        logger = mock {
            on { logRecordBuilder() } doReturn logRecordBuilder
        }
    }

    @Test
    fun `detects ANR when main thread blocked`() {
        val watchdog = AnrWatchdog(
            logger = logger,
            timeoutMs = 100,
            checkIntervalMs = 50
        )

        val anrDetected = watchdog.checkForAnr(mainThreadResponded = false)

        assertTrue(anrDetected)
    }

    @Test
    fun `does not detect ANR when main thread responds`() {
        val watchdog = AnrWatchdog(
            logger = logger,
            timeoutMs = 100,
            checkIntervalMs = 50
        )

        val anrDetected = watchdog.checkForAnr(mainThreadResponded = true)

        assertTrue(!anrDetected)
    }

    @Test
    fun `emits log with ERROR severity on ANR`() {
        val watchdog = AnrWatchdog(
            logger = logger,
            timeoutMs = 100,
            checkIntervalMs = 50
        )

        watchdog.reportAnr(Thread.currentThread().stackTrace)

        verify(logRecordBuilder).setSeverity(Severity.ERROR)
        verify(logRecordBuilder).emit()
    }

    @Test
    fun `includes main thread stacktrace in ANR report`() {
        val watchdog = AnrWatchdog(
            logger = logger,
            timeoutMs = 100,
            checkIntervalMs = 50
        )

        val stackTrace = Thread.currentThread().stackTrace
        watchdog.reportAnr(stackTrace)

        verify(logRecordBuilder).setAllAttributes(argThat { attrs ->
            attrs.asMap().any { (key, _) ->
                key.key == "main_thread.stacktrace"
            }
        })
    }
}
