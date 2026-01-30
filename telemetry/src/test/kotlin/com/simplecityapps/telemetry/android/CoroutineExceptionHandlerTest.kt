package com.simplecityapps.telemetry.android

import io.opentelemetry.api.logs.Logger
import io.opentelemetry.api.logs.LogRecordBuilder
import io.opentelemetry.api.logs.Severity
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Job
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*
import kotlin.coroutines.CoroutineContext

class CoroutineExceptionHandlerTest {

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
    fun `captures coroutine exception with ERROR severity`() {
        val handler = TelemetryCoroutineExceptionHandler()
        handler.setLogger(logger)

        val exception = IllegalStateException("Coroutine failed")
        val context: CoroutineContext = Job() + CoroutineName("test-coroutine")

        handler.handleException(context, exception)

        verify(logRecordBuilder).setSeverity(Severity.ERROR)
        verify(logRecordBuilder).emit()
    }

    @Test
    fun `includes coroutine name in attributes`() {
        val handler = TelemetryCoroutineExceptionHandler()
        handler.setLogger(logger)

        val exception = RuntimeException("Test")
        val context: CoroutineContext = Job() + CoroutineName("my-coroutine")

        handler.handleException(context, exception)

        verify(logRecordBuilder).setAllAttributes(argThat { attrs ->
            attrs.asMap().any { (key, value) ->
                key.key == "coroutine.name" && value == "my-coroutine"
            }
        })
    }

    @Test
    fun `handles missing coroutine name gracefully`() {
        val handler = TelemetryCoroutineExceptionHandler()
        handler.setLogger(logger)

        val exception = RuntimeException("Test")
        val context: CoroutineContext = Job()

        handler.handleException(context, exception)

        verify(logRecordBuilder).emit()
    }
}
