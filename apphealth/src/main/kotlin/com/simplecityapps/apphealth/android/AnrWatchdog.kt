package com.simplecityapps.apphealth.android

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.Handler
import android.os.Looper
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.logs.Logger
import io.opentelemetry.api.logs.Severity
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal class AnrWatchdog(
    private val logger: Logger,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val checkIntervalMs: Long = DEFAULT_CHECK_INTERVAL_MS
) {
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private var watchdogThread: Thread? = null
    private val running = AtomicBoolean(false)
    private val lastResponseTime = AtomicLong(System.currentTimeMillis())
    private val tickReceived = AtomicBoolean(true)

    private val tickRunnable = Runnable {
        tickReceived.set(true)
        lastResponseTime.set(System.currentTimeMillis())
    }

    private fun createWatchdogThread(): Thread = Thread({
        while (running.get()) {
            try {
                checkMainThread()
                Thread.sleep(checkIntervalMs)
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                // Continue monitoring
            }
        }
    }, "anr-watchdog").apply { isDaemon = true }

    fun start() {
        if (running.compareAndSet(false, true)) {
            lastResponseTime.set(System.currentTimeMillis())
            tickReceived.set(true)
            watchdogThread = createWatchdogThread()
            watchdogThread?.start()
        }
    }

    fun stop() {
        running.set(false)
        watchdogThread?.interrupt()
    }

    private fun checkMainThread() {
        if (Debug.isDebuggerConnected()) {
            return
        }

        tickReceived.set(false)
        mainHandler.post(tickRunnable)

        Thread.sleep(timeoutMs)

        if (!tickReceived.get()) {
            onAnrDetected()
        }
    }

    private fun onAnrDetected() {
        val mainThread = Looper.getMainLooper().thread
        val mainStackTrace = mainThread.stackTrace

        val allStackTraces = Thread.getAllStackTraces()

        reportAnr(mainStackTrace, allStackTraces)
    }

    internal fun checkForAnr(mainThreadResponded: Boolean): Boolean = !mainThreadResponded

    internal fun reportAnr(
        mainStackTrace: Array<StackTraceElement>,
        allStackTraces: Map<Thread, Array<StackTraceElement>> = emptyMap()
    ) {
        val mainStackString = mainStackTrace.joinToString("\n") { "\tat $it" }

        val otherStacksString = allStackTraces
            .filter { it.key.name != "main" }
            .entries
            .take(10)
            .joinToString("\n\n") { (thread, stack) ->
                "Thread: ${thread.name} (${thread.state})\n" +
                    stack.joinToString("\n") { "\tat $it" }
            }

        val attributes = Attributes.builder()
            .put(AttributeKey.stringKey("anr.type"), "watchdog")
            .put(AttributeKey.longKey("anr.timeout_ms"), timeoutMs)
            .put(AttributeKey.stringKey("anr.main_thread.stacktrace"), mainStackString)
            .put(AttributeKey.stringKey("anr.other_threads.stacktraces"), otherStacksString)
            .build()

        logger.logRecordBuilder()
            .setSeverity(Severity.ERROR)
            .setBody("ANR Detected: Main thread blocked for ${timeoutMs}ms")
            .setAllAttributes(attributes)
            .emit()
    }

    companion object {
        private const val DEFAULT_TIMEOUT_MS = 5000L
        private const val DEFAULT_CHECK_INTERVAL_MS = 1000L
        private const val PREFS_NAME = "telemetry_anr"
        private const val KEY_LAST_REPORTED_ANR_TIMESTAMP = "last_reported_anr_timestamp"

        fun checkHistoricalAnrs(context: Context, logger: Logger) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                return
            }

            try {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                    ?: return

                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val lastReportedTimestamp = prefs.getLong(KEY_LAST_REPORTED_ANR_TIMESTAMP, 0L)
                var newestTimestamp = lastReportedTimestamp

                val exitReasons = am.getHistoricalProcessExitReasons(
                    context.packageName,
                    0,
                    5
                )

                for (info in exitReasons) {
                    if (info.reason == ApplicationExitInfo.REASON_ANR) {
                        // Only report ANRs we haven't seen before
                        if (info.timestamp > lastReportedTimestamp) {
                            reportHistoricalAnr(logger, info)
                            if (info.timestamp > newestTimestamp) {
                                newestTimestamp = info.timestamp
                            }
                        }
                    }
                }

                // Update the last reported timestamp if we found newer ANRs
                if (newestTimestamp > lastReportedTimestamp) {
                    prefs.edit()
                        .putLong(KEY_LAST_REPORTED_ANR_TIMESTAMP, newestTimestamp)
                        .apply()
                }
            } catch (e: Exception) {
                // Silently ignore
            }
        }

        private fun reportHistoricalAnr(logger: Logger, info: ApplicationExitInfo) {
            val attributes = Attributes.builder()
                .put(AttributeKey.stringKey("anr.type"), "historical")
                .put(AttributeKey.longKey("anr.timestamp"), info.timestamp)
                .put(AttributeKey.stringKey("anr.description"), info.description ?: "")
                .put(AttributeKey.longKey("anr.pid"), info.pid.toLong())
                .build()

            logger.logRecordBuilder()
                .setSeverity(Severity.ERROR)
                .setBody("Historical ANR: ${info.description ?: "No description"}")
                .setAllAttributes(attributes)
                .emit()
        }
    }
}
