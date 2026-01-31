package com.simplecityapps.apphealth.android

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.logs.Logger
import io.opentelemetry.api.logs.Severity
import java.io.File

/**
 * Reads crash files written by [CrashStorage] and reports them via OpenTelemetry Logger.
 *
 * This is the read-side of the store-and-forward crash handling pattern. Crashes are
 * written to disk by [CrashStorage] when they occur, then read and reported on the next
 * app launch.
 *
 * ## JVM Crash File Format
 * - Line 0: Exception fully qualified class name (e.g., "java.lang.NullPointerException")
 * - Line 1: Exception message (may be empty)
 * - Line 2: Thread name
 * - Line 3: Thread ID
 * - Line 4+: Stacktrace
 *
 * ## Coroutine Crash File Format
 * - Line 0: Exception fully qualified class name
 * - Line 1: Exception message (may be empty)
 * - Line 2: Thread name
 * - Line 3: Thread ID
 * - Line 4: Coroutine name
 * - Line 5: Cancelled status ("true" or "false")
 * - Line 6+: Stacktrace
 */
internal class CrashReporter(private val logger: Logger) {

    /**
     * Checks for and reports any existing crash files.
     * Files are deleted after reporting (or on error).
     */
    fun checkAndReportCrashes(jvmCrashFile: File, coroutineCrashFile: File) {
        checkAndReportJvmCrash(jvmCrashFile)
        checkAndReportCoroutineCrash(coroutineCrashFile)
    }

    private fun checkAndReportJvmCrash(crashFile: File): Boolean {
        if (!crashFile.exists()) return false

        return runCatching {
            val content = crashFile.readText()
            if (content.isEmpty()) return false

            val lines = content.lines()
            val exceptionType = lines.getOrNull(0) ?: "unknown"
            val exceptionMessage = lines.getOrNull(1) ?: ""
            val threadName = lines.getOrNull(2) ?: "unknown"
            val threadId = lines.getOrNull(3)?.toLongOrNull() ?: 0L
            val stacktrace = lines.drop(4).joinToString("\n")

            val simpleTypeName = exceptionType.substringAfterLast('.')

            val attributes = Attributes.builder()
                .put(AttributeKey.stringKey("crash.type"), "jvm")
                .put(AttributeKey.stringKey("exception.type"), exceptionType)
                .put(AttributeKey.stringKey("exception.message"), exceptionMessage)
                .put(AttributeKey.stringKey("exception.stacktrace"), stacktrace)
                .put(AttributeKey.stringKey("thread.name"), threadName)
                .put(AttributeKey.longKey("thread.id"), threadId)
                .build()

            logger.logRecordBuilder()
                .setSeverity(Severity.ERROR)
                .setBody("JVM Crash: $simpleTypeName: $exceptionMessage")
                .setAllAttributes(attributes)
                .emit()

            true
        }.onSuccess {
            crashFile.delete()
        }.onFailure {
            crashFile.delete()
        }.getOrDefault(false)
    }

    private fun checkAndReportCoroutineCrash(crashFile: File): Boolean {
        if (!crashFile.exists()) return false

        return runCatching {
            val content = crashFile.readText()
            if (content.isEmpty()) return false

            val lines = content.lines()
            val exceptionType = lines.getOrNull(0) ?: "unknown"
            val exceptionMessage = lines.getOrNull(1) ?: ""
            val threadName = lines.getOrNull(2) ?: "unknown"
            val threadId = lines.getOrNull(3)?.toLongOrNull() ?: 0L
            val coroutineName = lines.getOrNull(4) ?: "unknown"
            val isCancelled = lines.getOrNull(5)?.toBoolean() ?: false
            val stacktrace = lines.drop(6).joinToString("\n")

            val simpleTypeName = exceptionType.substringAfterLast('.')

            val attributes = Attributes.builder()
                .put(AttributeKey.stringKey("crash.type"), "coroutine")
                .put(AttributeKey.stringKey("exception.type"), exceptionType)
                .put(AttributeKey.stringKey("exception.message"), exceptionMessage)
                .put(AttributeKey.stringKey("exception.stacktrace"), stacktrace)
                .put(AttributeKey.stringKey("thread.name"), threadName)
                .put(AttributeKey.longKey("thread.id"), threadId)
                .put(AttributeKey.stringKey("coroutine.name"), coroutineName)
                .put(AttributeKey.booleanKey("coroutine.cancelled"), isCancelled)
                .build()

            logger.logRecordBuilder()
                .setSeverity(Severity.ERROR)
                .setBody("Coroutine Crash: $simpleTypeName: $exceptionMessage")
                .setAllAttributes(attributes)
                .emit()

            true
        }.onSuccess {
            crashFile.delete()
        }.onFailure {
            crashFile.delete()
        }.getOrDefault(false)
    }
}
