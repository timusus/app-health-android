package com.simplecityapps.apphealth.android

import java.io.File
import java.io.FileOutputStream

/**
 * Writes crash data to disk for later reporting by [CrashReporter].
 *
 * This is the write-side of the store-and-forward crash handling pattern. When a crash
 * occurs, it is written to disk immediately. On the next app launch, [CrashReporter]
 * reads and reports the crash via OpenTelemetry Logger.
 *
 * ## JVM Crash File Format (jvm_crash.txt)
 * - Line 0: Exception fully qualified class name (e.g., "java.lang.NullPointerException")
 * - Line 1: Exception message (may be empty)
 * - Line 2: Thread name
 * - Line 3: Thread ID
 * - Line 4+: Stacktrace
 *
 * ## Coroutine Crash File Format (coroutine_crash.txt)
 * - Line 0: Exception fully qualified class name
 * - Line 1: Exception message (may be empty)
 * - Line 2: Thread name
 * - Line 3: Thread ID
 * - Line 4: Coroutine name
 * - Line 5: Cancelled status ("true" or "false")
 * - Line 6+: Stacktrace
 */
internal class CrashStorage(private val directory: File) {

    private val jvmCrashFile = File(directory, "jvm_crash.txt")
    private val coroutineCrashFile = File(directory, "coroutine_crash.txt")

    fun writeJvmCrash(thread: Thread, throwable: Throwable) {
        try {
            val content = buildString {
                appendLine(throwable.javaClass.name)
                appendLine(throwable.message ?: "")
                appendLine(thread.name)
                appendLine(thread.id)
                append(throwable.stackTraceToString())
            }
            writeToFile(jvmCrashFile, content)
        } catch (e: Exception) {
            // Never crash while handling a crash
        }
    }

    fun writeCoroutineCrash(
        thread: Thread,
        throwable: Throwable,
        coroutineName: String,
        isCancelled: Boolean
    ) {
        try {
            val content = buildString {
                appendLine(throwable.javaClass.name)
                appendLine(throwable.message ?: "")
                appendLine(thread.name)
                appendLine(thread.id)
                appendLine(coroutineName)
                appendLine(isCancelled)
                append(throwable.stackTraceToString())
            }
            writeToFile(coroutineCrashFile, content)
        } catch (e: Exception) {
            // Never crash while handling a crash
        }
    }

    private fun writeToFile(file: File, content: String) {
        FileOutputStream(file).use { fos ->
            fos.write(content.toByteArray(Charsets.UTF_8))
            fos.flush()
        }
    }
}
