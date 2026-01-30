package com.simplecityapps.telemetry.android

import io.opentelemetry.api.logs.Logger
import io.opentelemetry.api.logs.LogRecordBuilder
import io.opentelemetry.api.logs.Severity
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*
import kotlin.test.assertTrue

class CrashHandlerTest {

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
    fun `captures exception and emits log with ERROR severity`() {
        var chainedHandlerCalled = false
        val previousHandler = Thread.UncaughtExceptionHandler { _, _ ->
            chainedHandlerCalled = true
        }

        val handler = CrashHandler(logger, previousHandler)
        val exception = RuntimeException("Test crash")
        val thread = Thread.currentThread()

        handler.uncaughtException(thread, exception)

        verify(logRecordBuilder).setSeverity(Severity.ERROR)
        verify(logRecordBuilder).setBody(argThat<String> { contains("RuntimeException") })
        verify(logRecordBuilder).emit()
        assertTrue(chainedHandlerCalled, "Should chain to previous handler")
    }

    @Test
    fun `includes thread name in log`() {
        val handler = CrashHandler(logger, null)
        val exception = RuntimeException("Test crash")
        val thread = Thread("test-thread-name")

        handler.uncaughtException(thread, exception)

        verify(logRecordBuilder).setAllAttributes(argThat { attrs ->
            attrs.asMap().any { (key, value) ->
                key.key == "thread.name" && value == "test-thread-name"
            }
        })
    }

    @Test
    fun `handles null previous handler gracefully`() {
        val handler = CrashHandler(logger, null)
        val exception = RuntimeException("Test crash")

        handler.uncaughtException(Thread.currentThread(), exception)

        verify(logRecordBuilder).emit()
    }
}
