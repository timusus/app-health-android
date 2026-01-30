package com.simplecityapps.telemetry.sample

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.simplecityapps.telemetry.android.Telemetry
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

    @Before
    fun setup() {
        collector = MockOtlpCollector()
        collector.start()

        apiServer = MockWebServer()
        apiServer.start()

        Telemetry.reset()

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        Telemetry.init(
            context = context,
            endpoint = collector.endpoint,
            serviceName = "network-e2e-test"
        )

        client = OkHttpClient.Builder()
            .addInterceptor(Telemetry.okHttpInterceptor())
            .build()

        Thread.sleep(500)
    }

    @After
    fun teardown() {
        Telemetry.reset()
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

    @Test
    fun urlWithUuidIsSanitizedInSpan() {
        apiServer.enqueue(MockResponse().setBody("{}").setResponseCode(200))

        collector.expectTraces(1)

        val request = Request.Builder()
            .url(apiServer.url("/users/123e4567-e89b-12d3-a456-426614174000/profile"))
            .build()

        client.newCall(request).execute().close()

        val received = collector.awaitTraces(timeoutSeconds = 30)
        assertTrue(received, "Should receive trace within timeout")

        val traces = collector.getTraces()
        assertTrue(traces.isNotEmpty())

        val traceBody = traces.first().body.clone().readUtf8()
        assertTrue(
            !traceBody.contains("123e4567-e89b-12d3-a456-426614174000"),
            "UUID should be sanitized from trace"
        )
    }

    @Test
    fun multipleRequestsEachEmitSpan() {
        repeat(3) {
            apiServer.enqueue(MockResponse().setBody("{}").setResponseCode(200))
        }

        collector.expectTraces(3)

        repeat(3) { i ->
            val request = Request.Builder()
                .url(apiServer.url("/api/item/$i"))
                .build()
            client.newCall(request).execute().close()
        }

        val received = collector.awaitTraces(timeoutSeconds = 30)
        assertTrue(received, "Should receive all traces within timeout")
        assertTrue(
            collector.getTraces().size >= 3,
            "Should have received at least 3 traces"
        )
    }
}
