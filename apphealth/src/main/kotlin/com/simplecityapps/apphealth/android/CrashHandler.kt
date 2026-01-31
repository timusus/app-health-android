package com.simplecityapps.apphealth.android

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
    private var crashStorage: CrashStorage? = null

    override val key: CoroutineContext.Key<*> = kotlinx.coroutines.CoroutineExceptionHandler

    internal fun setCrashStorage(storage: CrashStorage) {
        this.crashStorage = storage
    }

    override fun handleException(context: CoroutineContext, exception: Throwable) {
        val storage = crashStorage ?: return

        try {
            val coroutineName = context[kotlinx.coroutines.CoroutineName]?.name ?: "unknown"
            val job = context[kotlinx.coroutines.Job]
            val currentThread = Thread.currentThread()
            val isCancelled = job?.isCancelled ?: false

            storage.writeCoroutineCrash(currentThread, exception, coroutineName, isCancelled)
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
