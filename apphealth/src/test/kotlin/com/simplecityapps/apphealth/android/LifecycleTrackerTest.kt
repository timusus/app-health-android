package com.simplecityapps.apphealth.android

import android.content.Context
import androidx.lifecycle.Lifecycle
import com.simplecityapps.apphealth.android.fakes.FakeSharedPreferences
import com.simplecityapps.apphealth.android.fakes.InMemoryTelemetry
import io.opentelemetry.api.common.AttributeKey
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
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
    fun `regenerates session on foreground after timeout`() {
        // Start with a valid session (background time is recent, session not expired)
        val existingSessionId = java.util.UUID.randomUUID().toString()
        val recentTime = System.currentTimeMillis() - (5 * 60 * 1000) // 5 min ago, within timeout
        prefs.edit()
            .putString("session_id", existingSessionId)
            .putLong("last_background_time", recentTime)
            .apply()

        // Create SessionManager - it should keep the existing session since not expired
        val context: Context = mock {
            on { getSharedPreferences(any(), any()) } doReturn prefs
            on { packageName } doReturn "com.test.app"
            on { applicationContext } doReturn it
        }
        val testSessionManager = SessionManager(context)

        // Verify we loaded the existing session (not expired on construction)
        assertEquals(existingSessionId, testSessionManager.sessionId)

        // Now simulate 31 minutes passing by updating the background time to be stale
        val thirtyOneMinutesAgo = System.currentTimeMillis() - (31 * 60 * 1000)
        prefs.edit()
            .putLong("last_background_time", thirtyOneMinutesAgo)
            .apply()

        // Trigger foreground event via LifecycleTracker
        val tracker = LifecycleTracker(telemetry.logger, testSessionManager, null)
        tracker.onStateChanged(Lifecycle.State.STARTED)

        // Session should have been regenerated due to timeout
        assertNotEquals(existingSessionId, testSessionManager.sessionId)
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
    fun `tracks foreground duration on background`() {
        val tracker = LifecycleTracker(telemetry.logger, sessionManager, null)

        tracker.onStateChanged(Lifecycle.State.STARTED)
        Thread.sleep(50)
        telemetry.reset()
        tracker.onStateChanged(Lifecycle.State.CREATED)

        val logs = telemetry.getLogRecords()
        val duration = logs[0].attributes.get(AttributeKey.longKey("app.foreground.duration_ms"))
        assertNotNull(duration)
        assertTrue(duration >= 50)
    }

    @Test
    fun `flushes telemetry on background when openTelemetry provided`() {
        // Create tracker with OpenTelemetry instance for flush
        val tracker = LifecycleTracker(telemetry.logger, sessionManager, telemetry.openTelemetry)

        tracker.onStateChanged(Lifecycle.State.STARTED)
        tracker.onStateChanged(Lifecycle.State.CREATED)

        // Flush should have been called - no exception means success
        val logs = telemetry.getLogRecords()
        assertEquals(2, logs.size) // foreground + background
    }

    @Test
    fun `handles null openTelemetry gracefully on background`() {
        val tracker = LifecycleTracker(telemetry.logger, sessionManager, null)

        tracker.onStateChanged(Lifecycle.State.STARTED)
        tracker.onStateChanged(Lifecycle.State.CREATED)

        // Should not throw - flush is skipped when openTelemetry is null
        val logs = telemetry.getLogRecords()
        assertEquals(2, logs.size)
    }
}
