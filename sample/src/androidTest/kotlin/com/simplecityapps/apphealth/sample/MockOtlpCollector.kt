package com.simplecityapps.apphealth.sample

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Mock OTLP collector that captures telemetry requests for verification.
 */
class MockOtlpCollector {

    private val server = MockWebServer()
    private val receivedTraces = CopyOnWriteArrayList<RecordedRequest>()
    private val receivedLogs = CopyOnWriteArrayList<RecordedRequest>()
    private val receivedMetrics = CopyOnWriteArrayList<RecordedRequest>()

    private var traceLatch: CountDownLatch? = null
    private var logLatch: CountDownLatch? = null
    private var metricLatch: CountDownLatch? = null

    val endpoint: String
        get() = server.url("/").toString().trimEnd('/')

    fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                when (request.path) {
                    "/v1/traces" -> {
                        receivedTraces.add(request)
                        traceLatch?.countDown()
                    }
                    "/v1/logs" -> {
                        receivedLogs.add(request)
                        logLatch?.countDown()
                    }
                    "/v1/metrics" -> {
                        receivedMetrics.add(request)
                        metricLatch?.countDown()
                    }
                }
                return MockResponse().setResponseCode(200)
            }
        }
        server.start()
    }

    fun stop() {
        server.shutdown()
    }

    fun expectTraces(count: Int) {
        traceLatch = CountDownLatch(count)
    }

    fun expectLogs(count: Int) {
        logLatch = CountDownLatch(count)
    }

    fun expectMetrics(count: Int) {
        metricLatch = CountDownLatch(count)
    }

    fun awaitTraces(timeoutSeconds: Long = 10): Boolean {
        return traceLatch?.await(timeoutSeconds, TimeUnit.SECONDS) ?: true
    }

    fun awaitLogs(timeoutSeconds: Long = 10): Boolean {
        return logLatch?.await(timeoutSeconds, TimeUnit.SECONDS) ?: true
    }

    fun awaitMetrics(timeoutSeconds: Long = 10): Boolean {
        return metricLatch?.await(timeoutSeconds, TimeUnit.SECONDS) ?: true
    }

    fun getTraces(): List<RecordedRequest> = receivedTraces.toList()

    fun getLogs(): List<RecordedRequest> = receivedLogs.toList()

    fun getMetrics(): List<RecordedRequest> = receivedMetrics.toList()

    fun clear() {
        receivedTraces.clear()
        receivedLogs.clear()
        receivedMetrics.clear()
        traceLatch = null
        logLatch = null
        metricLatch = null
    }

    fun hasTraceContaining(text: String): Boolean {
        return receivedTraces.any { request ->
            request.body.clone().readUtf8().contains(text)
        }
    }

    fun hasLogContaining(text: String): Boolean {
        return receivedLogs.any { request ->
            request.body.clone().readUtf8().contains(text)
        }
    }

    fun hasMetricContaining(text: String): Boolean {
        return receivedMetrics.any { request ->
            request.body.clone().readUtf8().contains(text)
        }
    }

    /**
     * Returns the raw body text of a specific log request.
     * Useful for detailed assertions.
     */
    fun getLogBody(index: Int): String? {
        return receivedLogs.getOrNull(index)?.body?.clone()?.readUtf8()
    }

    /**
     * Returns the raw body text of a specific trace request.
     */
    fun getTraceBody(index: Int): String? {
        return receivedTraces.getOrNull(index)?.body?.clone()?.readUtf8()
    }

    /**
     * Returns the raw body text of a specific metric request.
     */
    fun getMetricBody(index: Int): String? {
        return receivedMetrics.getOrNull(index)?.body?.clone()?.readUtf8()
    }

    /**
     * Checks if any log contains a specific attribute key-value pair pattern.
     * Note: This is a simple string search - for complex assertions, parse the body.
     */
    fun hasLogWithAttribute(key: String, value: String): Boolean {
        return receivedLogs.any { request ->
            val body = request.body.clone().readUtf8()
            body.contains(key) && body.contains(value)
        }
    }

    /**
     * Checks if any trace contains a specific attribute key-value pair pattern.
     */
    fun hasTraceWithAttribute(key: String, value: String): Boolean {
        return receivedTraces.any { request ->
            val body = request.body.clone().readUtf8()
            body.contains(key) && body.contains(value)
        }
    }
}
