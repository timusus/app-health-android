package com.simplecityapps.telemetry.android

import androidx.lifecycle.Lifecycle
import io.opentelemetry.api.logs.Logger
import io.opentelemetry.api.logs.LogRecordBuilder
import io.opentelemetry.api.logs.Severity
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*

class LifecycleTrackerTest {

    private lateinit var logger: Logger
    private lateinit var logRecordBuilder: LogRecordBuilder
    private lateinit var sessionManager: SessionManager

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
        sessionManager = mock()
    }

    @Test
    fun `emits foreground event on start`() {
        val tracker = LifecycleTracker(logger, sessionManager, null)

        tracker.onStateChanged(Lifecycle.State.STARTED)

        verify(logRecordBuilder).setBody("app.foreground")
        verify(logRecordBuilder).emit()
        verify(sessionManager).onForeground()
    }

    @Test
    fun `emits background event on stop`() {
        val tracker = LifecycleTracker(logger, sessionManager, null)

        tracker.onStateChanged(Lifecycle.State.STARTED)
        reset(logRecordBuilder, sessionManager)

        whenever(logRecordBuilder.setSeverity(any())).thenReturn(logRecordBuilder)
        whenever(logRecordBuilder.setBody(any<String>())).thenReturn(logRecordBuilder)
        whenever(logRecordBuilder.setAllAttributes(any())).thenReturn(logRecordBuilder)
        whenever(logger.logRecordBuilder()).thenReturn(logRecordBuilder)

        tracker.onStateChanged(Lifecycle.State.CREATED)

        verify(logRecordBuilder).setBody("app.background")
        verify(sessionManager).onBackground()
    }

    @Test
    fun `tracks session duration on background`() {
        val tracker = LifecycleTracker(logger, sessionManager, null)

        tracker.onStateChanged(Lifecycle.State.STARTED)
        reset(logRecordBuilder)

        whenever(logRecordBuilder.setSeverity(any())).thenReturn(logRecordBuilder)
        whenever(logRecordBuilder.setBody(any<String>())).thenReturn(logRecordBuilder)
        whenever(logRecordBuilder.setAllAttributes(any())).thenReturn(logRecordBuilder)
        whenever(logger.logRecordBuilder()).thenReturn(logRecordBuilder)

        Thread.sleep(50)
        tracker.onStateChanged(Lifecycle.State.CREATED)

        verify(logRecordBuilder).setAllAttributes(argThat { attrs ->
            attrs.asMap().any { (key, _) ->
                key.key == "session.duration_ms"
            }
        })
    }
}
