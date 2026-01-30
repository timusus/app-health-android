package com.simplecityapps.apphealth.android

import com.simplecityapps.apphealth.android.fakes.InMemoryTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.StatusCode
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    fun `identifies sensitive headers`() {
        assertTrue(UrlSanitizer.isSensitiveHeader("Authorization"))
        assertTrue(UrlSanitizer.isSensitiveHeader("Cookie"))
        assertTrue(UrlSanitizer.isSensitiveHeader("Set-Cookie"))
        assertTrue(UrlSanitizer.isSensitiveHeader("X-Api-Key"))
        assertTrue(UrlSanitizer.isSensitiveHeader("X-Auth-Token"))
        assertTrue(UrlSanitizer.isSensitiveHeader("Bearer-Token"))

        assertFalse(UrlSanitizer.isSensitiveHeader("Content-Type"))
        assertFalse(UrlSanitizer.isSensitiveHeader("Accept"))
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

class NetworkSamplingConfigTest {

    @Test
    fun `default successSampleRate is 1_0`() {
        val config = NetworkSamplingConfig()

        assertEquals(1.0, config.successSampleRate)
    }

    @Test
    fun `default maxErrorsPerMinute is 10`() {
        val config = NetworkSamplingConfig()

        assertEquals(10, config.maxErrorsPerMinute)
    }

    @Test
    fun `successSampleRate coerces to valid range`() {
        val config = NetworkSamplingConfig()

        config.successSampleRate = 1.5
        assertEquals(1.0, config.successSampleRate)

        config.successSampleRate = -0.5
        assertEquals(0.0, config.successSampleRate)

        config.successSampleRate = 0.5
        assertEquals(0.5, config.successSampleRate)
    }

    @Test
    fun `maxErrorsPerMinute coerces to non-negative`() {
        val config = NetworkSamplingConfig()

        config.maxErrorsPerMinute = -5
        assertEquals(0, config.maxErrorsPerMinute)

        config.maxErrorsPerMinute = 100
        assertEquals(100, config.maxErrorsPerMinute)
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
    fun `samples successful requests based on rate`() {
        val samplingConfig = NetworkSamplingConfig().apply {
            successSampleRate = 0.5
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

    @Test
    fun `rate limits error requests`() {
        val samplingConfig = NetworkSamplingConfig().apply {
            maxErrorsPerMinute = 2
        }

        val interceptor = NetworkInterceptor(
            urlSanitizerProvider = { null },
            samplingConfigProvider = { samplingConfig },
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
            .message("Error")
            .body("Error".toResponseBody())
            .build()

        val chain: Interceptor.Chain = mock {
            on { request() } doAnswer { request }
            on { proceed(any()) } doAnswer { mockResponse }
        }

        // First 2 requests should be sampled
        interceptor.intercept(chain)
        interceptor.intercept(chain)
        assertEquals(2, telemetry.getSpans().size)

        // Third request should be rate limited
        interceptor.intercept(chain)
        assertEquals(2, telemetry.getSpans().size) // Still 2
    }
}
