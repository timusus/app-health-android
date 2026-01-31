package com.simplecityapps.apphealth.android

import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.context.Context
import io.opentelemetry.context.propagation.TextMapPropagator
import io.opentelemetry.context.propagation.TextMapSetter
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

/**
 * OkHttp interceptor that creates OpenTelemetry spans for HTTP requests.
 *
 * Features:
 * - Automatic URL sanitization (strips IDs, UUIDs, query params)
 * - Configurable sampling rate
 * - OTel semantic conventions for HTTP spans
 * - W3C Trace Context propagation (traceparent/tracestate headers)
 *
 * @property urlSanitizerProvider Provides custom URL sanitizer, or null for default
 * @property samplingConfigProvider Provides sampling configuration
 * @property textMapPropagatorProvider Provides the propagator for trace context injection, or null to disable
 * @property tracerProvider Provides the Tracer instance, or null if SDK not ready
 */
internal class NetworkInterceptor(
    private val urlSanitizerProvider: () -> ((String) -> String)?,
    private val samplingConfigProvider: () -> NetworkConfig?,
    private val textMapPropagatorProvider: () -> TextMapPropagator? = { null },
    private val randomSource: () -> Double = Math::random,
    private val tracerProvider: () -> Tracer?
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val tracer = tracerProvider() ?: return chain.proceed(chain.request())
        val samplingConfig = samplingConfigProvider()
        val request = chain.request()

        // Sampling decision must be made before the request to enable trace context propagation
        if (!shouldSample(samplingConfig)) {
            return chain.proceed(request)
        }

        // Create span before request to enable trace context propagation
        val span = createRequestSpan(tracer, request)

        return try {
            span.makeCurrent().use {
                // Inject trace context headers into the request
                val tracedRequest = injectTraceContext(request, samplingConfig)

                val response = chain.proceed(tracedRequest)

                // Record response attributes
                span.setAttribute("http.response.status_code", response.code.toLong())

                response.body?.contentLength()?.let { size ->
                    if (size > 0) {
                        span.setAttribute("http.response.body.size", size)
                    }
                }

                if (response.code >= 400) {
                    span.setStatus(StatusCode.ERROR, "HTTP ${response.code}")
                }

                span.end()
                response
            }
        } catch (e: IOException) {
            span.setStatus(StatusCode.ERROR, e.message ?: "Network error")
            span.recordException(e)
            span.end()
            throw e
        }
    }

    private fun injectTraceContext(request: Request, config: NetworkConfig?): Request {
        if (config?.traceContextPropagation == false) return request

        val propagator = textMapPropagatorProvider() ?: return request

        val builder = request.newBuilder()
        propagator.inject(Context.current(), builder, OkHttpHeaderSetter)
        return builder.build()
    }

    private fun createRequestSpan(tracer: Tracer, request: okhttp3.Request): io.opentelemetry.api.trace.Span {
        val customSanitizer = urlSanitizerProvider()
        val sanitizedUrl = customSanitizer?.invoke(request.url.toString())
            ?: UrlSanitizer.sanitize(request.url.toString())

        val span = tracer.spanBuilder("HTTP ${request.method}")
            .setSpanKind(SpanKind.CLIENT)
            .setAttribute("http.request.method", request.method)
            .setAttribute("url.full", sanitizedUrl)
            .setAttribute("server.address", request.url.host)
            .setAttribute("server.port", request.url.port.toLong())
            .startSpan()

        request.body?.contentLength()?.let { size ->
            if (size > 0) {
                span.setAttribute("http.request.body.size", size)
            }
        }

        return span
    }

    private fun shouldSample(config: NetworkConfig?): Boolean {
        val rate = config?.sampleRate ?: 1.0
        return rate >= 1.0 || randomSource() < rate
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

    /**
     * Sanitizes a URL by removing query params and replacing IDs with placeholders.
     */
    fun sanitize(url: String): String = url
        .substringBefore("?")
        .replace(UUID_PATTERN, "{id}")
        .replace(NUMERIC_ID_PATTERN, "/{id}")
}

/**
 * TextMapSetter implementation for OkHttp Request.Builder.
 */
private object OkHttpHeaderSetter : TextMapSetter<Request.Builder> {
    override fun set(carrier: Request.Builder?, key: String, value: String) {
        carrier?.header(key, value)
    }
}
