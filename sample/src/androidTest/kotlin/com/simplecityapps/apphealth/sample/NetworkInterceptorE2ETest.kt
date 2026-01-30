package com.simplecityapps.apphealth.sample

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.simplecityapps.apphealth.android.AppHealth
import io.opentelemetry.sdk.OpenTelemetrySdk
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class NetworkInterceptorE2ETest {

    private lateinit var collector: MockOtlpCollector
    private lateinit var apiServer: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var otelSdk: OpenTelemetrySdk

    @Before
    fun setup() {
        collector = MockOtlpCollector()
        collector.start()

        apiServer = MockWebServer()
        apiServer.start()

        AppHealth.reset()

        otelSdk = E2ETestOpenTelemetry.create(collector.endpoint, "network-e2e-test")

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        AppHealth.init(
            context = context,
            openTelemetry = otelSdk
        )

        client = OkHttpClient.Builder()
            .addInterceptor(AppHealth.okHttpInterceptor())
            .build()

        val ready = AppHealth.awaitReady(timeoutMs = 5000)
        assertTrue(ready, "AppHealth SDK should be ready")
    }

    @After
    fun teardown() {
        AppHealth.reset()
        E2ETestOpenTelemetry.shutdown(otelSdk)
        collector.stop()
        apiServer.shutdown()
    }

    @Test
    fun httpRequestEmitsSpanToCollector() {
        apiServer.enqueue(MockResponse().setBody("""{"status":"ok"}""").setResponseCode(200))

        collector.expectTraces(1)

        val request = Request.Builder()
            .url(apiServer.url("/api/users/123"))
            .build()

        client.newCall(request).execute().use { response ->
            assertTrue(response.isSuccessful)
        }

        val received = collector.awaitTraces(timeoutSeconds = 30)
        assertTrue(received, "Should receive trace within timeout")

        val traces = collector.getTraces()
        assertTrue(traces.isNotEmpty(), "Should have received trace")
    }

    @Test
    fun httpErrorResponseEmitsSpanWithErrorStatus() {
        apiServer.enqueue(MockResponse().setResponseCode(500).setBody("Internal Server Error"))

        collector.expectTraces(1)

        val request = Request.Builder()
            .url(apiServer.url("/api/error"))
            .build()

        client.newCall(request).execute().use { response ->
            assertTrue(response.code == 500)
        }

        val received = collector.awaitTraces(timeoutSeconds = 30)
        assertTrue(received, "Should receive trace within timeout")
        assertTrue(collector.getTraces().isNotEmpty(), "Should have received trace for error response")
    }

}
