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
}
