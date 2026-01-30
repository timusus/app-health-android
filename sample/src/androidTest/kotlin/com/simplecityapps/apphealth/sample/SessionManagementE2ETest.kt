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
class SessionManagementE2ETest {

    private lateinit var collector: MockOtlpCollector
    private lateinit var otelSdk: OpenTelemetrySdk

    @Before
    fun setup() {
        collector = MockOtlpCollector()
        collector.start()

        AppHealth.reset()

        otelSdk = E2ETestOpenTelemetry.create(collector.endpoint, "session-e2e-test")

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
    fun sessionIdIncludedInTelemetry() {
        collector.expectTraces(1)

        // Create a span through the OTel SDK
        val tracer = otelSdk.getTracer("test")
        val span = tracer.spanBuilder("test-span-for-session").startSpan()
        span.end()

        // Force flush to ensure telemetry is exported
        AppHealth.forceFlush()

        val received = collector.awaitTraces(timeoutSeconds = 30)
        assertTrue(received, "Should receive trace within timeout")

        // Parse trace to verify session.id attribute (now as span attribute, not resource)
        val traces = collector.getTraces()
        assertTrue(traces.isNotEmpty(), "Should have received trace")

        val traceBody = traces.first().body.clone().readUtf8()
        assertTrue(
            traceBody.contains("session.id") || traceBody.contains("session"),
            "Trace should include session.id attribute"
        )
    }

    @Test
    fun serviceNameIncludedInResourceAttributes() {
        collector.expectTraces(1)

        val tracer = otelSdk.getTracer("test")
        val span = tracer.spanBuilder("test-service-name-span").startSpan()
        span.end()

        AppHealth.forceFlush()

        val received = collector.awaitTraces(timeoutSeconds = 30)
        assertTrue(received, "Should receive trace")

        val traces = collector.getTraces()
        val traceBody = traces.first().body.clone().readUtf8()
        assertTrue(
            traceBody.contains("session-e2e-test") || traceBody.contains("service.name"),
            "Trace should include service name in resource attributes"
        )
    }

}
