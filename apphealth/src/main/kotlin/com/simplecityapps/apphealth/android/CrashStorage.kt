package com.simplecityapps.apphealth.android

import java.io.File
import java.io.FileOutputStream

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
