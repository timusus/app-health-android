package com.simplecityapps.apphealth.sample

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
class AppHealthE2ETest {

    private lateinit var collector: MockOtlpCollector
    private lateinit var otelSdk: OpenTelemetrySdk

    @Before
    fun setup() {
        collector = MockOtlpCollector()
        collector.start()

        AppHealth.reset()

        otelSdk = E2ETestOpenTelemetry.create(collector.endpoint, "e2e-test")

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        AppHealth.init(
            context = context,
            openTelemetry = otelSdk
        )

        // Wait for async init to complete
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
    fun sdkInitializesSuccessfully() {
        assertTrue(AppHealth.isInitialized, "SDK should be initialized")
        assertTrue(AppHealth.isReady, "SDK should be ready")
    }

    @Test
    fun canCreateSpansThroughOtelSdk() {
        // Set expectation BEFORE creating spans
        collector.expectTraces(1)

        val tracer = otelSdk.getTracer("test-tracer")

        // Verify we can create spans through the OTel SDK
        val span = tracer.spanBuilder("test-span").startSpan()
        span.end()

        // Force flush to ensure spans are exported
        AppHealth.forceFlush()

        // The span should be exported
        val received = collector.awaitTraces(timeoutSeconds = 30)
        assertTrue(received, "Should receive trace from OTel SDK")
    }
}
