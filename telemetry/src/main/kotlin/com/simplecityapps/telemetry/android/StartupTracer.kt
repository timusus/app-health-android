package com.simplecityapps.telemetry.android

import android.app.Activity
import android.app.Application
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import io.opentelemetry.api.trace.Tracer
import java.util.concurrent.atomic.AtomicBoolean

internal class StartupTracer(
    private val tracer: Tracer,
    private val processStartTime: Long = getProcessStartElapsedRealtime()
) : Application.ActivityLifecycleCallbacks {

    private val ttidRecorded = AtomicBoolean(false)
    private val ttfdRecorded = AtomicBoolean(false)

    override fun onActivityResumed(activity: Activity) {
        if (ttidRecorded.compareAndSet(false, true)) {
            onFirstActivityResumed(SystemClock.elapsedRealtime())
        }
    }

    internal fun onFirstActivityResumed(currentTime: Long) {
        val durationMs = currentTime - processStartTime

        val span = tracer.spanBuilder("app.startup")
            .setAttribute("startup.type", "cold")
            .setAttribute("startup.duration_ms", durationMs)
            .startSpan()

        span.end()
    }

    fun reportFullyDrawn(currentTime: Long = SystemClock.elapsedRealtime()) {
        if (!ttfdRecorded.compareAndSet(false, true)) {
            return
        }

        val durationMs = currentTime - processStartTime

        val span = tracer.spanBuilder("app.startup.full")
            .setAttribute("startup.type", "cold")
            .setAttribute("startup.duration_ms", durationMs)
            .setAttribute("startup.fully_drawn", true)
            .startSpan()

        span.end()
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}

    companion object {
        fun getProcessStartElapsedRealtime(): Long =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                Process.getStartElapsedRealtime()
            } else {
                SystemClock.elapsedRealtime()
            }
    }
}
