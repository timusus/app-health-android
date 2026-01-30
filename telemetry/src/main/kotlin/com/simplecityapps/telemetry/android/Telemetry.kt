package com.simplecityapps.telemetry.android

import android.app.Application
import android.content.Context
import androidx.navigation.NavController
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.logs.Severity
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import okhttp3.Interceptor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

object Telemetry {

    private val initialized = AtomicBoolean(false)
    private val configRef = AtomicReference<OtelConfig?>(null)
    private val sessionManagerRef = AtomicReference<SessionManager?>(null)
    private val startupTracerRef = AtomicReference<Any?>(null)
    private val navigationTrackerRef = AtomicReference<Any?>(null)

    private val telemetryExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "telemetry-worker").apply { isDaemon = true }
    }
    private val telemetryDispatcher = telemetryExecutor.asCoroutineDispatcher()
    private val telemetryScope = CoroutineScope(SupervisorJob() + telemetryDispatcher)

    private var urlSanitizer: ((String) -> String)? = null

    val isInitialized: Boolean
        get() = initialized.get()

    fun init(
        context: Context,
        endpoint: String,
        serviceName: String,
        urlSanitizer: ((String) -> String)? = null
    ) {
        if (!initialized.compareAndSet(false, true)) {
            return
        }

        this.urlSanitizer = urlSanitizer
        val appContext = context.applicationContext

        telemetryScope.launch {
            try {
                val sessionManager = SessionManager(appContext)
                sessionManagerRef.set(sessionManager)

                val config = OtelConfig(
                    endpoint = endpoint,
                    serviceName = serviceName,
                    sessionId = sessionManager.sessionId,
                    deviceModel = sessionManager.getDeviceModel(),
                    deviceManufacturer = sessionManager.getDeviceManufacturer(),
                    osVersion = sessionManager.getOsVersion(),
                    appVersion = sessionManager.getAppVersion(appContext),
                    appVersionCode = sessionManager.getAppVersionCode(appContext)
                )
                configRef.set(config)

                initializeCollectors(appContext as Application)

            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to initialize telemetry", e)
            }
        }
    }

    private fun initializeCollectors(application: Application) {
        // Collectors will be wired up in Task 15
    }

    fun startSpan(name: String): Span {
        val config = configRef.get() ?: return NoOpSpan
        return try {
            val spanBuilder = config.tracer.spanBuilder(name)
            addUserAttributes(spanBuilder)
            spanBuilder.startSpan()
        } catch (e: Exception) {
            NoOpSpan
        }
    }

    fun logEvent(name: String, attributes: Map<String, Any> = emptyMap()) {
        val config = configRef.get() ?: return
        telemetryScope.launch {
            try {
                val builder = Attributes.builder()
                    .put(AttributeKey.stringKey("event.name"), name)

                attributes.forEach { (key, value) ->
                    when (value) {
                        is String -> builder.put(AttributeKey.stringKey(key), value)
                        is Long -> builder.put(AttributeKey.longKey(key), value)
                        is Int -> builder.put(AttributeKey.longKey(key), value.toLong())
                        is Double -> builder.put(AttributeKey.doubleKey(key), value)
                        is Boolean -> builder.put(AttributeKey.booleanKey(key), value)
                        else -> builder.put(AttributeKey.stringKey(key), value.toString())
                    }
                }

                addSessionAttributes(builder)

                config.logger.logRecordBuilder()
                    .setSeverity(Severity.INFO)
                    .setBody(name)
                    .setAllAttributes(builder.build())
                    .emit()
            } catch (e: Exception) {
                // Silently ignore
            }
        }
    }

    fun reportFullyDrawn() {
        // Will be connected to StartupTracer in Task 15
    }

    suspend fun trackNavigation(navController: NavController) {
        // Will be connected to NavigationTracker in Task 15
    }

    fun okHttpInterceptor(): Interceptor {
        return NetworkInterceptor { urlSanitizer }
    }

    fun setUserId(id: String?) {
        sessionManagerRef.get()?.setUserId(id)
    }

    fun setAttribute(key: String, value: String) {
        sessionManagerRef.get()?.setAttribute(key, value)
    }

    internal fun getConfig(): OtelConfig? = configRef.get()

    internal fun getSessionManager(): SessionManager? = sessionManagerRef.get()

    internal fun getTelemetryScope(): CoroutineScope = telemetryScope

    internal fun getUrlSanitizer(): ((String) -> String)? = urlSanitizer

    internal fun setStartupTracer(tracer: Any?) {
        startupTracerRef.set(tracer)
    }

    internal fun getStartupTracer(): Any? = startupTracerRef.get()

    internal fun setNavigationTracker(tracker: Any?) {
        navigationTrackerRef.set(tracker)
    }

    internal fun getNavigationTracker(): Any? = navigationTrackerRef.get()

    private fun addUserAttributes(spanBuilder: io.opentelemetry.api.trace.SpanBuilder) {
        val session = sessionManagerRef.get() ?: return
        session.userId?.let { spanBuilder.setAttribute("user.id", it) }
        session.customAttributes.forEach { (key, value) ->
            spanBuilder.setAttribute(key, value)
        }
    }

    private fun addSessionAttributes(builder: Attributes.Builder) {
        val session = sessionManagerRef.get() ?: return
        session.userId?.let { builder.put(AttributeKey.stringKey("user.id"), it) }
        session.customAttributes.forEach { (key, value) ->
            builder.put(AttributeKey.stringKey(key), value)
        }
    }

    internal fun reset() {
        configRef.get()?.shutdown()
        configRef.set(null)
        sessionManagerRef.set(null)
        startupTracerRef.set(null)
        navigationTrackerRef.set(null)
        initialized.set(false)
        urlSanitizer = null
    }

    private const val TAG = "Telemetry"

    private object NoOpSpan : Span {
        override fun <T : Any?> setAttribute(key: AttributeKey<T>, value: T): Span = this
        override fun addEvent(name: String, attributes: Attributes): Span = this
        override fun addEvent(name: String, attributes: Attributes, timestamp: Long, unit: TimeUnit): Span = this
        override fun addEvent(name: String): Span = this
        override fun addEvent(name: String, timestamp: Long, unit: TimeUnit): Span = this
        override fun setStatus(statusCode: StatusCode, description: String): Span = this
        override fun setStatus(statusCode: StatusCode): Span = this
        override fun recordException(exception: Throwable, additionalAttributes: Attributes): Span = this
        override fun recordException(exception: Throwable): Span = this
        override fun updateName(name: String): Span = this
        override fun end() {}
        override fun end(timestamp: Long, unit: TimeUnit) {}
        override fun getSpanContext() = io.opentelemetry.api.trace.SpanContext.getInvalid()
        override fun isRecording() = false
    }
}
