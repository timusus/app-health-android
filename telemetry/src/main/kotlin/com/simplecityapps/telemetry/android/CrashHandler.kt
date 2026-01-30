package com.simplecityapps.telemetry.android

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.logs.Logger
import io.opentelemetry.api.logs.Severity
import java.io.PrintWriter
import java.io.StringWriter

internal class CrashHandler(
    private val logger: Logger,
    private val previousHandler: Thread.UncaughtExceptionHandler?
) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            emitCrashLog(thread, throwable)
        } catch (e: Exception) {
            // Never crash while handling a crash
        } finally {
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun emitCrashLog(thread: Thread, throwable: Throwable) {
        val stackTrace = StringWriter().also { sw ->
            throwable.printStackTrace(PrintWriter(sw))
        }.toString()

        val attributes = Attributes.builder()
            .put(AttributeKey.stringKey("exception.type"), throwable.javaClass.name)
            .put(AttributeKey.stringKey("exception.message"), throwable.message ?: "")
            .put(AttributeKey.stringKey("exception.stacktrace"), stackTrace)
            .put(AttributeKey.stringKey("thread.name"), thread.name)
            .put(AttributeKey.longKey("thread.id"), thread.id)
            .put(AttributeKey.stringKey("crash.type"), "jvm")
            .build()

        logger.logRecordBuilder()
            .setSeverity(Severity.ERROR)
            .setBody("JVM Crash: ${throwable.javaClass.simpleName}: ${throwable.message}")
            .setAllAttributes(attributes)
            .emit()
    }

    companion object {
        fun install(logger: Logger) {
            val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
            val handler = CrashHandler(logger, previousHandler)
            Thread.setDefaultUncaughtExceptionHandler(handler)
        }
    }
}
