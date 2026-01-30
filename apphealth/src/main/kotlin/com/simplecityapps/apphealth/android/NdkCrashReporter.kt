package com.simplecityapps.apphealth.android

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.logs.Logger
import io.opentelemetry.api.logs.Severity
import java.io.File

/**
 * Handles reading and reporting native crash data from a crash file.
 * Separated from NdkCrashHandler to allow unit testing without native library loading.
 */
internal class NdkCrashReporter(private val logger: Logger) {

    /**
     * Checks for a previous crash file and reports it if found.
     * @return true if a crash was found and reported
     */
    fun checkAndReportCrash(crashFile: File): Boolean {
        if (!crashFile.exists()) return false

        return runCatching {
            val crashData = crashFile.readText()
            if (crashData.isNotEmpty()) {
                reportNativeCrash(crashData)
                true
            } else {
                false
            }
        }.onSuccess {
            crashFile.delete()
        }.onFailure {
            crashFile.delete()
        }.getOrDefault(false)
    }

    /**
     * Parses crash data and emits a log record.
     *
     * Expected format:
     * Line 0: Signal name (e.g., "SIGSEGV")
     * Line 1: Fault address
     * Line 2+: Backtrace
     */
    internal fun reportNativeCrash(crashData: String) {
        val lines = crashData.lines()
        val signal = lines.getOrNull(0) ?: "unknown"
        val faultAddress = lines.getOrNull(1) ?: "unknown"
        val backtrace = lines.drop(2).joinToString("\n")

        val attributes = Attributes.builder()
            .put(AttributeKey.stringKey("crash.type"), "native")
            .put(AttributeKey.stringKey("signal"), signal)
            .put(AttributeKey.stringKey("fault.address"), faultAddress)
            .put(AttributeKey.stringKey("backtrace"), backtrace)
            .build()

        logger.logRecordBuilder()
            .setSeverity(Severity.FATAL)
            .setBody("Native Crash: $signal")
            .setAllAttributes(attributes)
            .emit()
    }
}
