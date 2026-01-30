package com.simplecityapps.telemetry.android

import io.opentelemetry.api.trace.StatusCode
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

internal class NetworkInterceptor(
    private val urlSanitizerProvider: () -> ((String) -> String)?
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val config = Telemetry.getConfig() ?: return chain.proceed(chain.request())

        val request = chain.request()
        val customSanitizer = urlSanitizerProvider()
        val sanitizedUrl = customSanitizer?.invoke(request.url.toString())
            ?: UrlSanitizer.sanitize(request.url.toString())

        val span = config.tracer.spanBuilder("HTTP ${request.method}")
            .setAttribute("http.method", request.method)
            .setAttribute("http.url", sanitizedUrl)
            .setAttribute("http.target", request.url.encodedPath)
            .startSpan()

        request.body?.contentLength()?.let { size ->
            if (size > 0) {
                span.setAttribute("http.request.body.size", size)
            }
        }

        return try {
            val response = chain.proceed(request)

            span.setAttribute("http.status_code", response.code.toLong())

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
        } catch (e: IOException) {
            span.setStatus(StatusCode.ERROR, e.message ?: "Network error")
            span.recordException(e)
            span.end()
            throw e
        }
    }
}

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

    fun sanitize(url: String): String {
        val urlWithoutQuery = url.substringBefore("?")

        var sanitized = urlWithoutQuery.replace(UUID_PATTERN, "{id}")

        sanitized = sanitized.replace(NUMERIC_ID_PATTERN, "/{id}")

        return sanitized
    }

    fun isSensitiveHeader(headerName: String): Boolean {
        val lower = headerName.lowercase()

        if (lower in SENSITIVE_HEADERS) {
            return true
        }

        return SENSITIVE_HEADER_PATTERNS.any { pattern ->
            lower.contains(pattern)
        }
    }
}
