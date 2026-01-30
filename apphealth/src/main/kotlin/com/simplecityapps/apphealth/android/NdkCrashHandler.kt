package com.simplecityapps.apphealth.android

import android.content.Context
import io.opentelemetry.api.logs.Logger
import java.io.File

/**
 * Handles native (NDK) crash detection and reporting.
 *
 * Installs signal handlers for SIGSEGV, SIGABRT, SIGBUS, SIGFPE, SIGILL, and SIGTRAP.
 * When a native crash occurs, the signal handler writes crash data to a file.
 * On the next app launch, this data is read and reported via OpenTelemetry logs.
 *
 * @property context Application context for file storage
 * @property logger OpenTelemetry logger for crash reporting
 */
internal class NdkCrashHandler(
    private val context: Context,
    private val logger: Logger
) {
    private val crashFile: File = File(context.filesDir, CRASH_FILE_NAME)
    private val reporter = NdkCrashReporter(logger)

    init {
        System.loadLibrary("telemetry")
    }

    /**
     * Initializes the native crash handler.
     *
     * 1. Checks for and reports any crash from a previous session
     * 2. Installs signal handlers for native crash detection
     */
    fun initialize() {
        reporter.checkAndReportCrash(crashFile)
        nativeInit(crashFile.absolutePath)
    }

    @Throws(UnsatisfiedLinkError::class)
    private external fun nativeInit(crashFilePath: String)

    @Throws(UnsatisfiedLinkError::class)
    private external fun nativeTriggerTestCrash()

    /**
     * Triggers a native crash for testing purposes.
     *
     * **WARNING: This will crash the process immediately.**
     *
     * Only use in E2E tests with proper test orchestration.
     */
    internal fun triggerTestCrash() {
        nativeTriggerTestCrash()
    }

    companion object {
        private const val CRASH_FILE_NAME = "native_crash.txt"
    }
}
