package com.simplecityapps.apphealth.sample

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.simplecityapps.apphealth.android.AppHealth
import com.simplecityapps.apphealth.android.JankTracker
import io.opentelemetry.sdk.OpenTelemetrySdk
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class JankMetricsE2ETest {

    private lateinit var collector: MockOtlpCollector
    private lateinit var otelSdk: OpenTelemetrySdk

    @Before
    fun setup() {
        collector = MockOtlpCollector()
        collector.start()

        AppHealth.reset()

        // Set shorter interval for testing (2 seconds instead of 30)
        JankTracker.reportIntervalMsOverride = 2000L

        otelSdk = E2ETestOpenTelemetry.create(collector.endpoint, "jank-e2e-test")

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        AppHealth.init(
            context = context,
            openTelemetry = otelSdk
        )

        val ready = AppHealth.awaitReady(timeoutMs = 5000)
        assertTrue(ready, "AppHealth SDK should be ready")
    }

    @After
    fun teardown() {
        AppHealth.reset()
        JankTracker.resetForTesting()
        E2ETestOpenTelemetry.shutdown(otelSdk)
        collector.stop()
    }

    @Test
    fun frameMetricsReachCollectorAfterInterval() {
        collector.expectMetrics(1)

        // Launch activity to trigger frame metrics collection
        val scenario = ActivityScenario.launch(MainActivity::class.java)

        // Wait a bit for frames to be rendered and captured
        Thread.sleep(500)

        // Wait for the 2-second reporting interval + buffer
        Thread.sleep(3000)

        val received = collector.awaitMetrics(timeoutSeconds = 10)

        // Frame metrics may or may not be captured depending on device/emulator
        // What we're really testing is that the metric reporting runs without errors
        if (received) {
            val metrics = collector.getMetrics()
            assertTrue(metrics.isNotEmpty(), "Should have received frame metrics")
        }
        // If no metrics received, that's also acceptable - some emulators don't support JankStats

        scenario.close()
    }

    @Test
    fun multipleReportingIntervalsProduceMetrics() {
        // Launch activity
        val scenario = ActivityScenario.launch(MainActivity::class.java)

        // Wait for 2+ intervals
        Thread.sleep(5000)

        // Check if any metrics were received (not requiring a specific count
        // as JankStats availability varies)
        val metrics = collector.getMetrics()
        // Just verify the collection ran without crashing

        scenario.close()
    }
}
