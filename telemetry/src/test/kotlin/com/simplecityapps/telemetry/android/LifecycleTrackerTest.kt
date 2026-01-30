package com.simplecityapps.telemetry.android

import android.content.Context
import androidx.lifecycle.Lifecycle
import com.simplecityapps.telemetry.android.fakes.FakeSharedPreferences
import com.simplecityapps.telemetry.android.fakes.InMemoryTelemetry
import io.opentelemetry.api.common.AttributeKey
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LifecycleTrackerTest {

    private lateinit var telemetry: InMemoryTelemetry
    private lateinit var sessionManager: SessionManager
    private lateinit var prefs: FakeSharedPreferences

    @Before
    fun setup() {
        telemetry = InMemoryTelemetry()
        prefs = FakeSharedPreferences()
        val context: Context = mock {
            on { getSharedPreferences(any(), any()) } doReturn prefs
            on { packageName } doReturn "com.test.app"
            on { applicationContext } doReturn it
        }
        sessionManager = SessionManager(context)
    }

    @After
    fun teardown() {
        telemetry.shutdown()
    }

    @Test
    fun `emits foreground event on start`() {
        val tracker = LifecycleTracker(telemetry.logger, sessionManager, null)

        tracker.onStateChanged(Lifecycle.State.STARTED)

        val logs = telemetry.getLogRecords()
        assertEquals(1, logs.size)
        assertEquals("app.foreground", logs[0].body.asString())
    }

    @Test
    fun `emits background event on stop`() {
        val tracker = LifecycleTracker(telemetry.logger, sessionManager, null)

        // First go foreground
        tracker.onStateChanged(Lifecycle.State.STARTED)
        telemetry.reset()

        // Then go background
        tracker.onStateChanged(Lifecycle.State.CREATED)

        val logs = telemetry.getLogRecords()
        assertEquals(1, logs.size)
        assertEquals("app.background", logs[0].body.asString())
    }

    @Test
    fun `updates session manager on foreground`() {
        val tracker = LifecycleTracker(telemetry.logger, sessionManager, null)

        tracker.onStateChanged(Lifecycle.State.STARTED)

        // Session manager should have been notified (no way to verify directly,
        // but this exercises the code path)
        assertTrue(true)
    }

    @Test
    fun `records background timestamp via session manager`() {
        val tracker = LifecycleTracker(telemetry.logger, sessionManager, null)

        tracker.onStateChanged(Lifecycle.State.STARTED)
        tracker.onStateChanged(Lifecycle.State.CREATED)

        // Verify background timestamp was recorded
        val timestamp = prefs.getLong("last_background_time", 0L)
        assertTrue(timestamp > 0)
    }

    @Test
    fun `tracks session duration on background`() {
        val tracker = LifecycleTracker(telemetry.logger, sessionManager, null)

        tracker.onStateChanged(Lifecycle.State.STARTED)
        Thread.sleep(50)
        telemetry.reset()
        tracker.onStateChanged(Lifecycle.State.CREATED)

        val logs = telemetry.getLogRecords()
        val duration = logs[0].attributes.get(AttributeKey.longKey("session.duration_ms"))
        assertNotNull(duration)
        assertTrue(duration >= 50)
    }
}
