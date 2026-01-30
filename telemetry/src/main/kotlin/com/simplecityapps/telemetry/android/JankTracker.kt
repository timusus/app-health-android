package com.simplecityapps.telemetry.android

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.metrics.performance.JankStats
import androidx.metrics.performance.PerformanceMetricsState
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.metrics.Meter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

internal class JankTracker(
    private val meter: Meter,
    private val telemetryScope: CoroutineScope
) : Application.ActivityLifecycleCallbacks {

    private val aggregator = FrameMetricsAggregator()
    private val currentActivity = AtomicReference<Activity?>(null)
    private val currentJankStats = AtomicReference<JankStats?>(null)
    private var reportingJob: Job? = null

    private val totalCounter = meter.counterBuilder("frames.total")
        .setDescription("Total frames rendered")
        .build()

    private val slowCounter = meter.counterBuilder("frames.slow")
        .setDescription("Frames taking >16ms")
        .build()

    private val frozenCounter = meter.counterBuilder("frames.frozen")
        .setDescription("Frames taking >700ms")
        .build()

    private val jankStatsListener = JankStats.OnFrameListener { frameData ->
        aggregator.recordFrame(frameData.frameDurationUiNanos)
    }

    fun start() {
        reportingJob = telemetryScope.launch {
            while (isActive) {
                delay(REPORT_INTERVAL_MS)
                reportMetrics()
            }
        }
    }

    fun stop() {
        reportingJob?.cancel()
        currentJankStats.get()?.isTrackingEnabled = false
    }

    private fun reportMetrics() {
        val snapshot = aggregator.snapshot()
        aggregator.reset()
        if (snapshot.totalFrames == 0L) return

        val activityName = currentActivity.get()?.javaClass?.simpleName ?: "unknown"
        val attributes = Attributes.of(AttributeKey.stringKey("screen.name"), activityName)

        totalCounter.add(snapshot.totalFrames, attributes)
        slowCounter.add(snapshot.slowFrames, attributes)
        frozenCounter.add(snapshot.frozenFrames, attributes)
    }

    override fun onActivityResumed(activity: Activity) {
        currentActivity.set(activity)

        try {
            val holder = PerformanceMetricsState.getHolderForHierarchy(activity.window.decorView)
            val jankStats = JankStats.createAndTrack(activity.window, jankStatsListener)
            currentJankStats.set(jankStats)

            holder.state?.putState("Activity", activity.javaClass.simpleName)
        } catch (e: Exception) {
            // JankStats may not be available on all devices
        }
    }

    override fun onActivityPaused(activity: Activity) {
        if (currentActivity.get() == activity) {
            currentJankStats.get()?.isTrackingEnabled = false
            currentJankStats.set(null)
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}

    companion object {
        private const val REPORT_INTERVAL_MS = 30_000L
    }
}

internal class FrameMetricsAggregator {

    private var totalFrames: Long = 0L
    private var slowFrames: Long = 0L
    private var frozenFrames: Long = 0L

    @Synchronized
    fun recordFrame(durationNanos: Long) {
        totalFrames++
        val durationMs = durationNanos / 1_000_000
        if (durationMs > SLOW_THRESHOLD_MS) slowFrames++
        if (durationMs > FROZEN_THRESHOLD_MS) frozenFrames++
    }

    @Synchronized
    fun reset() {
        totalFrames = 0
        slowFrames = 0
        frozenFrames = 0
    }

    @Synchronized
    fun snapshot(): FrameMetricsSnapshot = FrameMetricsSnapshot(totalFrames, slowFrames, frozenFrames)

    companion object {
        private const val SLOW_THRESHOLD_MS = 16
        private const val FROZEN_THRESHOLD_MS = 700
    }
}

internal data class FrameMetricsSnapshot(
    val totalFrames: Long,
    val slowFrames: Long,
    val frozenFrames: Long
)
