package com.simplecityapps.apphealth.android

import com.simplecityapps.apphealth.android.fakes.InMemoryTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.propagation.ContextPropagators
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// URL Sanitization Tests

class UrlSanitizerTest {

    @Test
    fun `sanitizes UUIDs in URL path`() {
        val url = "https://api.example.com/users/123e4567-e89b-12d3-a456-426614174000/profile"
        val sanitized = UrlSanitizer.sanitize(url)

        assertEquals("https://api.example.com/users/{id}/profile", sanitized)
    }

    @Test
    fun `sanitizes numeric IDs in URL path`() {
        val url = "https://api.example.com/posts/12345/comments/67890"
        val sanitized = UrlSanitizer.sanitize(url)

        assertEquals("https://api.example.com/posts/{id}/comments/{id}", sanitized)
    }

    @Test
    fun `strips query parameters by default`() {
        val url = "https://api.example.com/search?query=test&page=1&token=secret"
        val sanitized = UrlSanitizer.sanitize(url)

        assertEquals("https://api.example.com/search", sanitized)
    }

    @Test
    fun `custom sanitizer overrides default`() {
        val customSanitizer: (String) -> String = { url ->
            url.replace(Regex("/v[0-9]+/"), "/v{version}/")
        }

        val url = "https://api.example.com/v2/users"
        val sanitized = customSanitizer(url)

        assertEquals("https://api.example.com/v{version}/users", sanitized)
    }
}

class NetworkConfigTest {

    @Test
    fun `default sampleRate is 1_0`() {
        val config = NetworkConfig()

        assertEquals(1.0, config.sampleRate)
    }

    @Test
    fun `default traceContextPropagation is true`() {
        val config = NetworkConfig()

        assertTrue(config.traceContextPropagation)
    }

    @Test
    fun `sampleRate coerces to valid range`() {
        val config = NetworkConfig()

        config.sampleRate = 1.5
        assertEquals(1.0, config.sampleRate)

        config.sampleRate = -0.5
        assertEquals(0.0, config.sampleRate)

        config.sampleRate = 0.5
        assertEquals(0.5, config.sampleRate)
    }
}

class NetworkInterceptorBehaviorTest {

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
    fun `creates span with correct HTTP attributes for successful request`() {
        val interceptor = NetworkInterceptor(
            urlSanitizerProvider = { null },
            samplingConfigProvider = { null },
            tracerProvider = { telemetry.tracer }
        )

        val request = Request.Builder()
            .url("https://api.example.com/users/123")
            .get()
            .build()

        val mockResponse = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body("{}".toResponseBody())
            .build()

        val chain: Interceptor.Chain = mock {
            on { request() } doAnswer { request }
            on { proceed(any()) } doAnswer { mockResponse }
        }

        interceptor.intercept(chain)

        val spans = telemetry.getSpans()
        assertEquals(1, spans.size)

        val span = spans[0]
        assertEquals("HTTP GET", span.name)
        assertEquals("GET", span.attributes.get(AttributeKey.stringKey("http.request.method")))
        assertEquals(200L, span.attributes.get(AttributeKey.longKey("http.response.status_code")))
        assertEquals("api.example.com", span.attributes.get(AttributeKey.stringKey("server.address")))
    }

    @Test
    fun `sanitizes URL with default sanitizer`() {
        val interceptor = NetworkInterceptor(
            urlSanitizerProvider = { null },
            samplingConfigProvider = { null },
            tracerProvider = { telemetry.tracer }
        )

        val request = Request.Builder()
            .url("https://api.example.com/users/12345/posts/67890")
            .get()
            .build()

        val mockResponse = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body("{}".toResponseBody())
            .build()

        val chain: Interceptor.Chain = mock {
            on { request() } doAnswer { request }
            on { proceed(any()) } doAnswer { mockResponse }
        }

        interceptor.intercept(chain)

        val spans = telemetry.getSpans()
        val sanitizedUrl = spans[0].attributes.get(AttributeKey.stringKey("url.full"))
        assertEquals("https://api.example.com/users/{id}/posts/{id}", sanitizedUrl)

        // Verify url.path is NOT present (it would leak unsanitized IDs)
        assertNull(spans[0].attributes.get(AttributeKey.stringKey("url.path")))
    }

    @Test
    fun `uses custom sanitizer when provided`() {
        val customSanitizer: (String) -> String = { url ->
            url.replace(Regex("/users/[^/]+"), "/users/REDACTED")
        }

        val interceptor = NetworkInterceptor(
            urlSanitizerProvider = { customSanitizer },
            samplingConfigProvider = { null },
            tracerProvider = { telemetry.tracer }
        )

        val request = Request.Builder()
            .url("https://api.example.com/users/john@example.com/profile")
            .get()
            .build()

        val mockResponse = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body("{}".toResponseBody())
            .build()

        val chain: Interceptor.Chain = mock {
            on { request() } doAnswer { request }
            on { proceed(any()) } doAnswer { mockResponse }
        }

        interceptor.intercept(chain)

        val spans = telemetry.getSpans()
        val sanitizedUrl = spans[0].attributes.get(AttributeKey.stringKey("url.full"))
        assertEquals("https://api.example.com/users/REDACTED/profile", sanitizedUrl)
    }

    @Test
    fun `sets error status for 4xx response`() {
        val interceptor = NetworkInterceptor(
            urlSanitizerProvider = { null },
            samplingConfigProvider = { null },
            tracerProvider = { telemetry.tracer }
        )

        val request = Request.Builder()
            .url("https://api.example.com/users")
            .get()
            .build()

        val mockResponse = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(404)
            .message("Not Found")
            .body("Not Found".toResponseBody())
            .build()

        val chain: Interceptor.Chain = mock {
            on { request() } doAnswer { request }
            on { proceed(any()) } doAnswer { mockResponse }
        }

        interceptor.intercept(chain)

        val spans = telemetry.getSpans()
        assertEquals(1, spans.size)
        assertEquals(StatusCode.ERROR, spans[0].status.statusCode)
        assertEquals(404L, spans[0].attributes.get(AttributeKey.longKey("http.response.status_code")))
    }

    @Test
    fun `sets error status for 5xx response`() {
        val interceptor = NetworkInterceptor(
            urlSanitizerProvider = { null },
            samplingConfigProvider = { null },
            tracerProvider = { telemetry.tracer }
        )

        val request = Request.Builder()
            .url("https://api.example.com/users")
            .get()
            .build()

        val mockResponse = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(500)
            .message("Internal Server Error")
            .body("Error".toResponseBody())
            .build()

        val chain: Interceptor.Chain = mock {
            on { request() } doAnswer { request }
            on { proceed(any()) } doAnswer { mockResponse }
        }

        interceptor.intercept(chain)

        val spans = telemetry.getSpans()
        assertEquals(1, spans.size)
        assertEquals(StatusCode.ERROR, spans[0].status.statusCode)
        assertEquals(500L, spans[0].attributes.get(AttributeKey.longKey("http.response.status_code")))
    }

    @Test
    fun `handles network exception gracefully`() {
        val interceptor = NetworkInterceptor(
            urlSanitizerProvider = { null },
            samplingConfigProvider = { null },
            tracerProvider = { telemetry.tracer }
        )

        val request = Request.Builder()
            .url("https://api.example.com/users")
            .get()
            .build()

        val chain: Interceptor.Chain = mock {
            on { request() } doAnswer { request }
            on { proceed(any()) } doAnswer { throw IOException("Connection refused") }
        }

        try {
            interceptor.intercept(chain)
        } catch (e: IOException) {
            // Expected
        }

        val spans = telemetry.getSpans()
        assertEquals(1, spans.size)
        assertEquals(StatusCode.ERROR, spans[0].status.statusCode)
        assertEquals("HTTP GET", spans[0].name)
    }

    @Test
    fun `skips span creation when tracer is null`() {
        val interceptor = NetworkInterceptor(
            urlSanitizerProvider = { null },
            samplingConfigProvider = { null },
            tracerProvider = { null } // Simulate SDK not initialized
        )

        val request = Request.Builder()
            .url("https://api.example.com/users")
            .get()
            .build()

        val mockResponse = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body("{}".toResponseBody())
            .build()

        val chain: Interceptor.Chain = mock {
            on { request() } doAnswer { request }
            on { proceed(any()) } doAnswer { mockResponse }
        }

        interceptor.intercept(chain)

        val spans = telemetry.getSpans()
        assertEquals(0, spans.size)
    }

    @Test
    fun `samples requests based on rate`() {
        val samplingConfig = NetworkConfig().apply {
            sampleRate = 0.5
        }

        // Test with random value below threshold (should sample)
        var interceptor = NetworkInterceptor(
            urlSanitizerProvider = { null },
            samplingConfigProvider = { samplingConfig },
            randomSource = { 0.3 }, // Below 0.5
            tracerProvider = { telemetry.tracer }
        )

        val request = Request.Builder()
            .url("https://api.example.com/users")
            .get()
            .build()

        val mockResponse = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body("{}".toResponseBody())
            .build()

        val chain: Interceptor.Chain = mock {
            on { request() } doAnswer { request }
            on { proceed(any()) } doAnswer { mockResponse }
        }

        interceptor.intercept(chain)
        assertEquals(1, telemetry.getSpans().size)

        // Reset and test with random value above threshold (should not sample)
        telemetry.reset()

        interceptor = NetworkInterceptor(
            urlSanitizerProvider = { null },
            samplingConfigProvider = { samplingConfig },
            randomSource = { 0.7 }, // Above 0.5
            tracerProvider = { telemetry.tracer }
        )

        interceptor.intercept(chain)
        assertEquals(0, telemetry.getSpans().size)
    }
}

class NetworkInterceptorTraceContextTest {

    private lateinit var telemetry: InMemoryTelemetry

    @Before
    fun setup() {
        telemetry = InMemoryTelemetry(
            propagators = ContextPropagators.create(W3CTraceContextPropagator.getInstance())
        )
    }

    @After
    fun teardown() {
        telemetry.shutdown()
    }

    @Test
    fun `injects traceparent header into outgoing request`() {
        val requestCaptor = argumentCaptor<Request>()

        val interceptor = NetworkInterceptor(
            urlSanitizerProvider = { null },
            samplingConfigProvider = { null },
            textMapPropagatorProvider = { telemetry.openTelemetry.propagators.textMapPropagator },
            tracerProvider = { telemetry.tracer }
        )

        val request = Request.Builder()
            .url("https://api.example.com/users")
            .get()
            .build()

        val mockResponse = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body("{}".toResponseBody())
            .build()

        val chain: Interceptor.Chain = mock {
            on { request() } doAnswer { request }
            on { proceed(requestCaptor.capture()) } doAnswer { mockResponse }
        }

        interceptor.intercept(chain)

        val capturedRequest = requestCaptor.firstValue
        val traceparent = capturedRequest.header("traceparent")

        assertNotNull(traceparent, "traceparent header should be injected")

        // W3C format: 00-{trace_id}-{span_id}-{flags}
        val parts = traceparent.split("-")
        assertEquals(4, parts.size, "traceparent should have 4 parts")
        assertEquals("00", parts[0], "version should be 00")
        assertEquals(32, parts[1].length, "trace_id should be 32 hex chars")
        assertEquals(16, parts[2].length, "span_id should be 16 hex chars")

        // Verify the trace ID in header matches the span's trace ID
        val spans = telemetry.getSpans()
        assertEquals(1, spans.size)
        assertEquals(spans[0].traceId, parts[1])
    }

    @Test
    fun `does not inject headers when request is not sampled`() {
        val requestCaptor = argumentCaptor<Request>()

        val samplingConfig = NetworkConfig().apply {
            sampleRate = 0.0 // Never sample
        }

        val interceptor = NetworkInterceptor(
            urlSanitizerProvider = { null },
            samplingConfigProvider = { samplingConfig },
            textMapPropagatorProvider = { telemetry.openTelemetry.propagators.textMapPropagator },
            tracerProvider = { telemetry.tracer }
        )

        val request = Request.Builder()
            .url("https://api.example.com/users")
            .get()
            .build()

        val mockResponse = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body("{}".toResponseBody())
            .build()

        val chain: Interceptor.Chain = mock {
            on { request() } doAnswer { request }
            on { proceed(requestCaptor.capture()) } doAnswer { mockResponse }
        }

        interceptor.intercept(chain)

        val capturedRequest = requestCaptor.firstValue
        val traceparent = capturedRequest.header("traceparent")

        assertNull(traceparent, "traceparent header should NOT be injected when not sampled")
        assertEquals(0, telemetry.getSpans().size)
    }

    @Test
    fun `does not inject headers when propagator is null`() {
        val requestCaptor = argumentCaptor<Request>()

        val interceptor = NetworkInterceptor(
            urlSanitizerProvider = { null },
            samplingConfigProvider = { null },
            textMapPropagatorProvider = { null }, // No propagator
            tracerProvider = { telemetry.tracer }
        )

        val request = Request.Builder()
            .url("https://api.example.com/users")
            .get()
            .build()

        val mockResponse = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body("{}".toResponseBody())
            .build()

        val chain: Interceptor.Chain = mock {
            on { request() } doAnswer { request }
            on { proceed(requestCaptor.capture()) } doAnswer { mockResponse }
        }

        interceptor.intercept(chain)

        val capturedRequest = requestCaptor.firstValue
        val traceparent = capturedRequest.header("traceparent")

        assertNull(traceparent, "traceparent header should NOT be injected when propagator is null")

        // Span should still be created
        assertEquals(1, telemetry.getSpans().size)
    }

    @Test
    fun `does not inject headers when traceContextPropagation is disabled`() {
        val requestCaptor = argumentCaptor<Request>()

        val samplingConfig = NetworkConfig().apply {
            traceContextPropagation = false
        }

        val interceptor = NetworkInterceptor(
            urlSanitizerProvider = { null },
            samplingConfigProvider = { samplingConfig },
            textMapPropagatorProvider = { telemetry.openTelemetry.propagators.textMapPropagator },
            tracerProvider = { telemetry.tracer }
        )

        val request = Request.Builder()
            .url("https://api.example.com/users")
            .get()
            .build()

        val mockResponse = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body("{}".toResponseBody())
            .build()

        val chain: Interceptor.Chain = mock {
            on { request() } doAnswer { request }
            on { proceed(requestCaptor.capture()) } doAnswer { mockResponse }
        }

        interceptor.intercept(chain)

        val capturedRequest = requestCaptor.firstValue
        val traceparent = capturedRequest.header("traceparent")

        assertNull(traceparent, "traceparent header should NOT be injected when disabled")

        // Span should still be created
        assertEquals(1, telemetry.getSpans().size)
    }
}
