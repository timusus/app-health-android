package com.simplecityapps.apphealth.android

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.logs.Logger
import io.opentelemetry.api.logs.Severity
import kotlin.coroutines.CoroutineContext

internal class CrashHandler(
    private val crashStorage: CrashStorage,
    private val previousHandler: Thread.UncaughtExceptionHandler?
) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            crashStorage.writeJvmCrash(thread, throwable)
        } catch (e: Exception) {
            // Never crash while handling a crash
        } finally {
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        fun install(crashStorage: CrashStorage) {
            val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
            val handler = CrashHandler(crashStorage, previousHandler)
            Thread.setDefaultUncaughtExceptionHandler(handler)
        }
    }
}

class AppHealthCoroutineExceptionHandler : kotlinx.coroutines.CoroutineExceptionHandler {

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
            val currentThread = Thread.currentThread()

            val attributes = Attributes.builder()
                .put(AttributeKey.stringKey("exception.type"), exception.javaClass.name)
                .put(AttributeKey.stringKey("exception.message"), exception.message ?: "")
                .put(AttributeKey.stringKey("exception.stacktrace"), exception.stackTraceToString())
                .put(AttributeKey.stringKey("thread.name"), currentThread.name)
                .put(AttributeKey.longKey("thread.id"), currentThread.id)
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
        private var instance: AppHealthCoroutineExceptionHandler? = null

        fun getInstance(): AppHealthCoroutineExceptionHandler {
            return instance ?: synchronized(this) {
                instance ?: AppHealthCoroutineExceptionHandler().also { instance = it }
            }
        }
    }
}
