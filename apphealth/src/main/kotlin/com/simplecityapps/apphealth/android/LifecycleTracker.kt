package com.simplecityapps.apphealth.android

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.logs.Logger
import io.opentelemetry.api.logs.Severity
import io.opentelemetry.sdk.OpenTelemetrySdk
import java.util.concurrent.TimeUnit

internal class LifecycleTracker(
    private val logger: Logger,
    private val openTelemetry: OpenTelemetry?
) : DefaultLifecycleObserver {

    private var foregroundStartTime: Long = 0L
    private var isInForeground = false

    override fun onStart(owner: LifecycleOwner) {
        onStateChanged(Lifecycle.State.STARTED)
    }

    override fun onStop(owner: LifecycleOwner) {
        onStateChanged(Lifecycle.State.CREATED)
    }

    internal fun onStateChanged(state: Lifecycle.State) {
        val shouldBeInForeground = state.isAtLeast(Lifecycle.State.STARTED)

        if (shouldBeInForeground && !isInForeground) {
            isInForeground = true
            foregroundStartTime = System.currentTimeMillis()
            onForeground()
        } else if (!shouldBeInForeground && isInForeground) {
            isInForeground = false
            onBackground()
        }
    }

    private fun onForeground() {
        emitLifecycleEvent("app.foreground")
    }

    private fun onBackground() {
        val foregroundDurationMs = System.currentTimeMillis() - foregroundStartTime

        emitLifecycleEvent(
            "app.background",
            mapOf("app.foreground.duration_ms" to foregroundDurationMs)
        )

        forceFlush()
    }

    private fun forceFlush() {
        val sdk = openTelemetry as? OpenTelemetrySdk ?: return
        runCatching {
            sdk.sdkTracerProvider.forceFlush().join(2, TimeUnit.SECONDS)
            sdk.sdkLoggerProvider.forceFlush().join(2, TimeUnit.SECONDS)
        }
    }

    private fun emitLifecycleEvent(eventName: String, extraAttributes: Map<String, Long> = emptyMap()) {
        val builder = Attributes.builder()
            .put(AttributeKey.stringKey("event.name"), eventName)

        extraAttributes.forEach { (key, value) ->
            builder.put(AttributeKey.longKey(key), value)
        }

        logger.logRecordBuilder()
            .setSeverity(Severity.INFO)
            .setBody(eventName)
            .setAllAttributes(builder.build())
            .emit()
    }

    companion object {
        fun register(
            logger: Logger,
            openTelemetry: OpenTelemetry?
        ): LifecycleTracker {
            val tracker = LifecycleTracker(logger, openTelemetry)
            ProcessLifecycleOwner.get().lifecycle.addObserver(tracker)
            return tracker
        }
    }
}
