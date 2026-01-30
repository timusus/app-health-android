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

/**
 * Tests SDK behavior when the OTLP collector is unavailable.
 * Verifies graceful degradation - the SDK should not crash or block the app.
 */
@RunWith(AndroidJUnit4::class)
class CollectorUnavailableE2ETest {

    private var otelSdk: OpenTelemetrySdk? = null

    @Before
    fun setup() {
        AppHealth.reset()
    }

    @After
    fun teardown() {
        AppHealth.reset()
        otelSdk?.let { E2ETestOpenTelemetry.shutdown(it) }
    }

    @Test
    fun sdkInitializesWithUnreachableEndpoint() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        // Create SDK with an endpoint that doesn't exist
        otelSdk = E2ETestOpenTelemetry.create("http://localhost:9999", "unreachable-test")

        // Initialize with the SDK
        AppHealth.init(
            context = context,
            openTelemetry = otelSdk!!
        )

        // SDK should still initialize (async init may fail silently for network)
        assertTrue(AppHealth.isInitialized, "SDK should be initialized even with unreachable endpoint")

        // Wait for async init - it should complete even if network fails
        val ready = AppHealth.awaitReady(timeoutMs = 10000)
        assertTrue(ready, "SDK should become ready even with unreachable endpoint")
    }

    @Test
    fun telemetryOperationsDoNotBlockWhenCollectorUnavailable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        otelSdk = E2ETestOpenTelemetry.create("http://localhost:9999", "unreachable-test")

        AppHealth.init(
            context = context,
            openTelemetry = otelSdk!!
        )

        AppHealth.awaitReady(timeoutMs = 10000)

        // Measure time to create and end a span
        val startTime = System.currentTimeMillis()

        val tracer = otelSdk!!.getTracer("test")
        repeat(10) { i ->
            val span = tracer.spanBuilder("test-span-$i").startSpan()
            span.end()
        }

        val duration = System.currentTimeMillis() - startTime

        // Creating spans should be fast (< 1 second for 10 spans)
        // even when the collector is unreachable
        assertTrue(
            duration < 1000,
            "Span creation should not block when collector unavailable (took ${duration}ms)"
        )
    }

    @Test
    fun forceFlushCompletesWhenCollectorUnavailable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        otelSdk = E2ETestOpenTelemetry.create("http://localhost:9999", "unreachable-test")

        AppHealth.init(
            context = context,
            openTelemetry = otelSdk!!
        )

        AppHealth.awaitReady(timeoutMs = 10000)

        // Create some telemetry
        val tracer = otelSdk!!.getTracer("test")
        val span = tracer.spanBuilder("test-span").startSpan()
        span.end()

        // forceFlush should complete (not hang) even when collector is unreachable
        val startTime = System.currentTimeMillis()
        val flushed = AppHealth.forceFlush()
        val duration = System.currentTimeMillis() - startTime

        assertTrue(flushed, "forceFlush should return true")
        assertTrue(
            duration < 5000,
            "forceFlush should complete within timeout (took ${duration}ms)"
        )
    }

}
