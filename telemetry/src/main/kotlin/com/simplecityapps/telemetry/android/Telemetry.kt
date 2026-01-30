package com.simplecityapps.telemetry.android

import android.app.Application
import android.content.Context
import androidx.navigation.NavController
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.common.AttributesBuilder
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
    private val startupTracerRef = AtomicReference<StartupTracer?>(null)
    private val navigationTrackerRef = AtomicReference<NavigationTracker?>(null)
    private val ndkCrashHandlerRef = AtomicReference<NdkCrashHandler?>(null)

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
        if (!initialized.compareAndSet(false, true)) return
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
        val config = configRef.get() ?: return
        val sessionManager = sessionManagerRef.get() ?: return
        val logger = config.logger

        // 1. JVM Crash Handler
        CrashHandler.install(logger)

        // 2. Coroutine Exception Handler
        TelemetryCoroutineExceptionHandler.getInstance().setLogger(logger)

        // 3. ANR Watchdog
        val anrWatchdog = AnrWatchdog(logger)
        anrWatchdog.start()
        AnrWatchdog.checkHistoricalAnrs(application, logger)

        // 4. Startup Tracer
        val startupTracer = StartupTracer(config.tracer)
        startupTracerRef.set(startupTracer)
        application.registerActivityLifecycleCallbacks(startupTracer)

        // 5. Lifecycle Tracker
        LifecycleTracker.register(logger, sessionManager, config)

        // 6. Jank Tracker
        val jankTracker = JankTracker(config.meter, telemetryScope)
        application.registerActivityLifecycleCallbacks(jankTracker)
        jankTracker.start()

        // 7. Navigation Tracker
        val navigationTracker = NavigationTracker(config.tracer)
        navigationTrackerRef.set(navigationTracker)

        // 8. NDK Crash Handler (wrapped in try-catch for UnsatisfiedLinkError)
        try {
            val ndkCrashHandler = NdkCrashHandler(application, logger)
            ndkCrashHandler.initialize()
            ndkCrashHandlerRef.set(ndkCrashHandler)
        } catch (e: UnsatisfiedLinkError) {
            android.util.Log.w(TAG, "Native library not available, NDK crash handling disabled", e)
        }
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
        startupTracerRef.get()?.reportFullyDrawn()
    }

    suspend fun trackNavigation(navController: NavController) {
        navigationTrackerRef.get()?.track(navController)
    }

    fun okHttpInterceptor(): Interceptor = NetworkInterceptor { urlSanitizer }

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
    internal fun setStartupTracer(tracer: StartupTracer?) = startupTracerRef.set(tracer)
    internal fun getStartupTracer(): StartupTracer? = startupTracerRef.get()
    internal fun setNavigationTracker(tracker: NavigationTracker?) = navigationTrackerRef.set(tracker)
    internal fun getNavigationTracker(): NavigationTracker? = navigationTrackerRef.get()

    private fun addUserAttributes(spanBuilder: io.opentelemetry.api.trace.SpanBuilder) {
        val session = sessionManagerRef.get() ?: return
        session.userId?.let { spanBuilder.setAttribute("user.id", it) }
        session.customAttributes.forEach { (key, value) ->
            spanBuilder.setAttribute(key, value)
        }
    }

    private fun addSessionAttributes(builder: AttributesBuilder) {
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
        ndkCrashHandlerRef.set(null)
        initialized.set(false)
        urlSanitizer = null
    }

    /**
     * Triggers a native crash for testing purposes.
     * WARNING: This will crash the process immediately.
     * Only use in E2E tests with proper test orchestration.
     */
    internal fun triggerNativeCrashForTesting() {
        ndkCrashHandlerRef.get()?.triggerTestCrash()
            ?: throw IllegalStateException("NDK crash handler not initialized")
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
