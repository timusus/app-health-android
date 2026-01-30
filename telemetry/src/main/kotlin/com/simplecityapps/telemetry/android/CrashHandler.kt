package com.simplecityapps.telemetry.android

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.logs.Logger
import io.opentelemetry.api.logs.Severity
import kotlin.coroutines.CoroutineContext

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
        val stackTrace = throwable.stackTraceToString()
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

class TelemetryCoroutineExceptionHandler : kotlinx.coroutines.CoroutineExceptionHandler {

    @Volatile
    private var logger: Logger? = null

    override val key: CoroutineContext.Key<*> = kotlinx.coroutines.CoroutineExceptionHandler

    fun setLogger(logger: Logger) {
        this.logger = logger
    }

    override fun handleException(context: CoroutineContext, exception: Throwable) {
        val log = logger ?: return

        try {
            val coroutineName = context[kotlinx.coroutines.CoroutineName]?.name ?: "unknown"
            val job = context[kotlinx.coroutines.Job]

            val attributes = Attributes.builder()
                .put(AttributeKey.stringKey("exception.type"), exception.javaClass.name)
                .put(AttributeKey.stringKey("exception.message"), exception.message ?: "")
                .put(AttributeKey.stringKey("exception.stacktrace"), exception.stackTraceToString())
                .put(AttributeKey.stringKey("coroutine.name"), coroutineName)
                .put(AttributeKey.booleanKey("coroutine.cancelled"), job?.isCancelled ?: false)
                .put(AttributeKey.stringKey("crash.type"), "coroutine")
                .build()

            log.logRecordBuilder()
                .setSeverity(Severity.ERROR)
                .setBody("Coroutine Crash: ${exception.javaClass.simpleName}: ${exception.message}")
                .setAllAttributes(attributes)
                .emit()
        } catch (e: Exception) {
            // Never crash while handling a crash
        }
    }

    companion object {
        @Volatile
        private var instance: TelemetryCoroutineExceptionHandler? = null

        fun getInstance(): TelemetryCoroutineExceptionHandler {
            return instance ?: synchronized(this) {
                instance ?: TelemetryCoroutineExceptionHandler().also { instance = it }
            }
        }
    }
}
