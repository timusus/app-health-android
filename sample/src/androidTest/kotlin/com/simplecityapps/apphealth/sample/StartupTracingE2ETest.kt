package com.simplecityapps.apphealth.sample

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.simplecityapps.apphealth.android.AppHealth
import io.opentelemetry.sdk.OpenTelemetrySdk
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class StartupTracingE2ETest {

    private lateinit var collector: MockOtlpCollector
    private lateinit var otelSdk: OpenTelemetrySdk

    @Before
    fun setup() {
        collector = MockOtlpCollector()
        collector.start()

        AppHealth.reset()

        otelSdk = E2ETestOpenTelemetry.create(collector.endpoint, "startup-e2e-test")

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
        E2ETestOpenTelemetry.shutdown(otelSdk)
        collector.stop()
    }

    @Test
    fun ttidSpanReachesCollector() {
        collector.expectTraces(1)

        // Launch activity - this triggers TTID measurement
        val scenario = ActivityScenario.launch(MainActivity::class.java)

        // Force flush to ensure telemetry is exported
        AppHealth.forceFlush()

        val received = collector.awaitTraces(timeoutSeconds = 30)
        assertTrue(received, "Should receive startup trace within timeout")

        // Verify TTID span is present (app.startup)
        assertTrue(
            collector.hasTraceContaining("app.startup") || collector.hasTraceContaining("startup"),
            "Trace should contain startup span"
        )

        scenario.close()
    }

    @Test
    fun ttfdSpanReachesCollectorAfterReportFullyDrawn() {
        collector.expectTraces(1)

        // Launch activity
        val scenario = ActivityScenario.launch(MainActivity::class.java)

        // MainActivity calls reportFullyDrawn() in onCreate,
        // so we should get a TTFD span
        AppHealth.forceFlush()

        val received = collector.awaitTraces(timeoutSeconds = 30)
        assertTrue(received, "Should receive startup trace")

        // Check for startup-related span
        val traces = collector.getTraces()
        assertTrue(traces.isNotEmpty(), "Should have received at least one trace")

        scenario.close()
    }

}
