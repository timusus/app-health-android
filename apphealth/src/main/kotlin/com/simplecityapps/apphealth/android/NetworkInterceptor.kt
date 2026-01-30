package com.simplecityapps.apphealth.android

import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * OkHttp interceptor that creates OpenTelemetry spans for HTTP requests.
 *
 * Features:
 * - Automatic URL sanitization (strips IDs, UUIDs, query params)
 * - Configurable success sampling rate
 * - Rate-limited error capture to prevent flooding during outages
 * - OTel semantic conventions for HTTP spans
 *
 * @property urlSanitizerProvider Provides custom URL sanitizer, or null for default
 * @property samplingConfigProvider Provides sampling configuration
 * @property tracerProvider Provides the Tracer instance, or null if SDK not ready
 */
internal class NetworkInterceptor(
    private val urlSanitizerProvider: () -> ((String) -> String)?,
    private val samplingConfigProvider: () -> NetworkSamplingConfig?,
    private val randomSource: () -> Double = Math::random,
    private val tracerProvider: () -> Tracer?
) : Interceptor {

    @Volatile
    private var errorRateLimiter: RateLimiter? = null
    private var lastMaxErrorsPerMinute: Int = -1

    override fun intercept(chain: Interceptor.Chain): Response {
        val tracer = tracerProvider() ?: return chain.proceed(chain.request())
        val samplingConfig = samplingConfigProvider()

        val request = chain.request()

        // Execute request first to determine if it's an error
        val response: Response = try {
            chain.proceed(request)
        } catch (e: IOException) {
            // Network error - check if we should sample before creating span
            if (shouldSampleError(samplingConfig)) {
                createSpanForException(tracer, request, e)
            }
            throw e
        }

        // Determine if we should sample this response
        val isError = response.code >= 400
        val shouldSample = if (isError) {
            shouldSampleError(samplingConfig)
        } else {
            shouldSampleSuccess(samplingConfig)
        }

        if (!shouldSample) {
            return response
        }

        // Create span only if sampled
        val customSanitizer = urlSanitizerProvider()
        val sanitizedUrl = customSanitizer?.invoke(request.url.toString())
            ?: UrlSanitizer.sanitize(request.url.toString())

        val span = tracer.spanBuilder("HTTP ${request.method}")
            .setSpanKind(SpanKind.CLIENT)
            .setAttribute("http.request.method", request.method)
            .setAttribute("url.full", sanitizedUrl)
            .setAttribute("url.path", request.url.encodedPath)
            .setAttribute("server.address", request.url.host)
            .setAttribute("server.port", request.url.port.toLong())
            .startSpan()

        request.body?.contentLength()?.let { size ->
            if (size > 0) {
                span.setAttribute("http.request.body.size", size)
            }
        }

        span.setAttribute("http.response.status_code", response.code.toLong())

        response.body?.contentLength()?.let { size ->
            if (size > 0) {
                span.setAttribute("http.response.body.size", size)
            }
        }

        if (isError) {
            span.setStatus(StatusCode.ERROR, "HTTP ${response.code}")
        }

        span.end()
        return response
    }

    private fun createSpanForException(tracer: Tracer, request: okhttp3.Request, e: IOException) {
        val customSanitizer = urlSanitizerProvider()
        val sanitizedUrl = customSanitizer?.invoke(request.url.toString())
            ?: UrlSanitizer.sanitize(request.url.toString())

        val span = tracer.spanBuilder("HTTP ${request.method}")
            .setSpanKind(SpanKind.CLIENT)
            .setAttribute("http.request.method", request.method)
            .setAttribute("url.full", sanitizedUrl)
            .setAttribute("url.path", request.url.encodedPath)
            .setAttribute("server.address", request.url.host)
            .setAttribute("server.port", request.url.port.toLong())
            .startSpan()

        request.body?.contentLength()?.let { size ->
            if (size > 0) {
                span.setAttribute("http.request.body.size", size)
            }
        }

        span.setStatus(StatusCode.ERROR, e.message ?: "Network error")
        span.recordException(e)
        span.end()
    }

    private fun shouldSampleSuccess(config: NetworkSamplingConfig?): Boolean {
        val rate = config?.successSampleRate ?: 1.0
        return rate >= 1.0 || randomSource() < rate
    }

    private fun shouldSampleError(config: NetworkSamplingConfig?): Boolean {
        val maxErrors = config?.maxErrorsPerMinute ?: 10
        // Recreate rate limiter if config changed
        if (errorRateLimiter == null || lastMaxErrorsPerMinute != maxErrors) {
            errorRateLimiter = RateLimiter(maxErrors)
            lastMaxErrorsPerMinute = maxErrors
        }
        return errorRateLimiter?.tryAcquire() ?: true
    }
}

/**
 * Default URL sanitizer for network spans.
 *
 * Removes potentially sensitive or high-cardinality data:
 * - Query parameters (may contain tokens, user data)
 * - UUIDs in path segments → `{id}`
 * - Numeric IDs in path segments → `{id}`
 */
internal object UrlSanitizer {

    private val UUID_PATTERN = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    private val NUMERIC_ID_PATTERN = Regex("/[0-9]+(?=/|$)")

    private val SENSITIVE_HEADERS = setOf(
        "authorization",
        "cookie",
        "set-cookie",
        "x-api-key"
    )

    private val SENSITIVE_HEADER_PATTERNS = listOf(
        "token",
        "auth",
        "key",
        "secret",
        "password",
        "credential"
    )

    /**
     * Sanitizes a URL by removing query params and replacing IDs with placeholders.
     */
    fun sanitize(url: String): String = url
        .substringBefore("?")
        .replace(UUID_PATTERN, "{id}")
        .replace(NUMERIC_ID_PATTERN, "/{id}")

    /**
     * Returns true if the header name indicates sensitive data (auth, tokens, etc.).
     */
    fun isSensitiveHeader(headerName: String): Boolean {
        val lower = headerName.lowercase()
        return lower in SENSITIVE_HEADERS || SENSITIVE_HEADER_PATTERNS.any { lower.contains(it) }
    }
}
