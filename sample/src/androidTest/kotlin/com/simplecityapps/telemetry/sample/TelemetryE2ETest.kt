package com.simplecityapps.telemetry.sample

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.simplecityapps.telemetry.android.Telemetry
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class TelemetryE2ETest {

    private lateinit var collector: MockOtlpCollector

    @Before
    fun setup() {
        collector = MockOtlpCollector()
        collector.start()

        Telemetry.reset()

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        Telemetry.init(
            context = context,
            endpoint = collector.endpoint,
            serviceName = "e2e-test"
        )

        // Wait for async init
        Thread.sleep(500)
    }

    @After
    fun teardown() {
        Telemetry.reset()
        collector.stop()
    }

    @Test
    fun customEventEmitsLogToCollector() {
        collector.expectLogs(1)

        Telemetry.logEvent("test.custom_event", mapOf(
            "key1" to "value1",
            "key2" to 42
        ))

        val received = collector.awaitLogs(timeoutSeconds = 30)
        assertTrue(received, "Should receive log within timeout")
        assertTrue(collector.getLogs().isNotEmpty(), "Should have received at least one log")
    }

    @Test
    fun customSpanEmitsTraceToCollector() {
        collector.expectTraces(1)

        val span = Telemetry.startSpan("test.custom_span")
        Thread.sleep(50)
        span.end()

        val received = collector.awaitTraces(timeoutSeconds = 30)
        assertTrue(received, "Should receive trace within timeout")
        assertTrue(collector.getTraces().isNotEmpty(), "Should have received at least one trace")
    }

    @Test
    fun setUserIdIncludedInSubsequentTelemetry() {
        Telemetry.setUserId("test-user-123")

        collector.expectLogs(1)
        Telemetry.logEvent("test.with_user_id")

        val received = collector.awaitLogs(timeoutSeconds = 30)
        assertTrue(received, "Should receive log within timeout")

        val hasUserId = collector.getLogs().any { request ->
            val body = request.body.clone().readUtf8()
            body.contains("user.id") || body.contains("test-user-123")
        }
        assertTrue(hasUserId, "Log should contain user ID")
    }

    @Test
    fun multipleEventsAllReachCollector() {
        val eventCount = 5
        collector.expectLogs(eventCount)

        repeat(eventCount) { i ->
            Telemetry.logEvent("test.batch_event_$i", mapOf("index" to i))
        }

        val received = collector.awaitLogs(timeoutSeconds = 30)
        assertTrue(received, "Should receive all logs within timeout")
        assertTrue(
            collector.getLogs().size >= eventCount,
            "Should have received at least $eventCount logs, got ${collector.getLogs().size}"
        )
    }
}
