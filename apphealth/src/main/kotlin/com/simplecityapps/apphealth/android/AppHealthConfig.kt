package com.simplecityapps.apphealth.android

/**
 * Configuration for the App Health SDK.
 *
 * All collectors are enabled by default. Use the DSL to disable specific features:
 *
 * ```kotlin
 * AppHealth.init(context, openTelemetry) {
 *     anrDetection = false
 *     ndkCrashHandling = false
 * }
 * ```
 */
class AppHealthConfig internal constructor() {

    /**
     * Enable JVM crash handling via UncaughtExceptionHandler.
     * Default: true
     */
    var crashHandling: Boolean = true

    /**
     * Enable coroutine exception handling.
     * Default: true
     */
    var coroutineCrashHandling: Boolean = true

    /**
     * Enable ANR detection via main thread watchdog.
     * Default: true
     */
    var anrDetection: Boolean = true

    /**
     * Enable NDK (native) crash handling.
     * Requires native library; gracefully disabled if unavailable.
     * Default: true
     */
    var ndkCrashHandling: Boolean = true

    /**
     * Enable app lifecycle tracking (foreground/background events).
     * Default: true
     */
    var lifecycleTracking: Boolean = true

    /**
     * Enable startup timing (TTID/TTFD measurement).
     * Default: true
     */
    var startupTracking: Boolean = true

    /**
     * Enable frame metrics tracking via JankStats.
     * Default: true
     */
    var jankTracking: Boolean = true

    /**
     * Custom URL sanitizer for network spans.
     * By default, UUIDs, numeric IDs, and query parameters are stripped.
     */
    var urlSanitizer: ((String) -> String)? = null

    /**
     * Network tracing configuration.
     */
    val networkConfig: NetworkConfig = NetworkConfig()

    /**
     * Configure network tracing.
     */
    fun networkConfig(configure: NetworkConfig.() -> Unit) {
        networkConfig.apply(configure)
    }
}

/**
 * Configuration for network tracing.
 */
class NetworkConfig internal constructor() {

    /**
     * Sample rate for HTTP requests.
     * 1.0 = capture all, 0.1 = capture 10%, 0.0 = capture none.
     * Default: 1.0 (capture all)
     *
     * For error-specific sampling, configure a custom Sampler on your OpenTelemetry SDK.
     */
    var sampleRate: Double = 1.0
        set(value) { field = value.coerceIn(0.0, 1.0) }

    /**
     * Enable W3C Trace Context propagation (traceparent/tracestate headers).
     *
     * When enabled, the interceptor injects trace context headers into outgoing
     * requests, allowing backend services to correlate their traces with the
     * Android client. Requires propagators to be configured on the OpenTelemetry SDK.
     *
     * Default: true
     */
    var traceContextPropagation: Boolean = true
}
