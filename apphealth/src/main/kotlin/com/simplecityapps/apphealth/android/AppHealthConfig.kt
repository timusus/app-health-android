package com.simplecityapps.apphealth.android

/**
 * Configuration for the App Health SDK.
 *
 * All collectors are enabled by default. Use the DSL to disable specific features:
 *
 * ```kotlin
 * AppHealth.init(context, endpoint, serviceName) {
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
     * Enable automatic session tracking.
     *
     * When enabled, AppHealth adds a `session.id` attribute to all spans and logs.
     * Sessions rotate on cold start or after 30 minutes in background.
     *
     * This helps correlate telemetry within a user session without requiring
     * manual instrumentation.
     *
     * Default: true
     */
    var sessionTracking: Boolean = true

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
     * Generate and include a persistent installation ID in telemetry.
     *
     * The installation ID is a random UUID generated on first app launch and stored
     * in SharedPreferences. It allows correlating telemetry from the same device
     * across sessions (e.g., distinguishing "100 crashes from 1 device" vs "100 devices").
     *
     * **Privacy considerations:**
     * - The ID is a random UUID, not derived from hardware identifiers
     * - It is stored only in app-private SharedPreferences
     * - It is deleted when the app is uninstalled
     * - It is not shared with other apps
     *
     * Set to `false` to disable for GDPR compliance or if device correlation is not needed.
     * Default: true
     */
    var installationIdEnabled: Boolean = true

    /**
     * Custom URL sanitizer for network spans.
     * By default, UUIDs, numeric IDs, and query parameters are stripped.
     */
    var urlSanitizer: ((String) -> String)? = null

    /**
     * Network request sampling configuration.
     * By default, all requests are captured (with error rate limiting).
     */
    val networkSampling: NetworkSamplingConfig = NetworkSamplingConfig()

    /**
     * Configure network request sampling.
     */
    fun networkSampling(configure: NetworkSamplingConfig.() -> Unit) {
        networkSampling.apply(configure)
    }
}

/**
 * Configuration for network request sampling.
 *
 * Allows controlling the volume of network telemetry to prevent overwhelming backends.
 */
class NetworkSamplingConfig internal constructor() {

    /**
     * Sample rate for successful HTTP requests (2xx/3xx).
     * 1.0 = capture all, 0.1 = capture 10%, 0.0 = capture none.
     * Default: 1.0 (capture all)
     */
    var successSampleRate: Double = 1.0
        set(value) { field = value.coerceIn(0.0, 1.0) }

    /**
     * Maximum error spans per minute.
     * Prevents flooding telemetry backend during outages.
     * Default: 10 per minute
     */
    var maxErrorsPerMinute: Int = 10
        set(value) { field = value.coerceAtLeast(0) }
}
