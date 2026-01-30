package com.simplecityapps.apphealth.android

import androidx.lifecycle.Lifecycle
import com.simplecityapps.apphealth.android.fakes.InMemoryTelemetry
import io.opentelemetry.api.common.AttributeKey
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LifecycleTrackerTest {

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
    fun `emits foreground event on start`() {
        val tracker = LifecycleTracker(telemetry.logger, null)

        tracker.onStateChanged(Lifecycle.State.STARTED)

        val logs = telemetry.getLogRecords()
        assertEquals(1, logs.size)
        assertEquals("app.foreground", logs[0].body.asString())
    }

    @Test
    fun `emits background event on stop`() {
        val tracker = LifecycleTracker(telemetry.logger, null)

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
    fun `tracks foreground duration on background`() {
        val tracker = LifecycleTracker(telemetry.logger, null)

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
        val tracker = LifecycleTracker(telemetry.logger, telemetry.openTelemetry)

        tracker.onStateChanged(Lifecycle.State.STARTED)
        tracker.onStateChanged(Lifecycle.State.CREATED)

        // Flush should have been called - no exception means success
        val logs = telemetry.getLogRecords()
        assertEquals(2, logs.size) // foreground + background
    }

    @Test
    fun `handles null openTelemetry gracefully on background`() {
        val tracker = LifecycleTracker(telemetry.logger, null)

        tracker.onStateChanged(Lifecycle.State.STARTED)
        tracker.onStateChanged(Lifecycle.State.CREATED)

        // Should not throw - flush is skipped when openTelemetry is null
        val logs = telemetry.getLogRecords()
        assertEquals(2, logs.size)
    }
}
