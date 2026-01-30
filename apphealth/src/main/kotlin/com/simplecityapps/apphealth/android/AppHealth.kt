package com.simplecityapps.apphealth.android

import android.app.Application
import android.content.Context
import androidx.navigation.NavController
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.logs.Logger
import io.opentelemetry.api.metrics.Meter
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.sdk.OpenTelemetrySdk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import okhttp3.Interceptor
import java.util.concurrent.TimeUnit

/**
 * Android App Health SDK.
 *
 * Automatically collects crashes, ANRs, performance metrics, and other telemetry
 * using an app-provided OpenTelemetry SDK.
 *
 * ## Basic Usage
 *
 * ```kotlin
 * // In Application.onCreate()
 * val otelSdk = OpenTelemetrySdk.builder()
 *     .setResource(Resource.create(Attributes.of(
 *         ServiceAttributes.SERVICE_NAME, "my-app"
 *     )))
 *     .setTracerProvider(SdkTracerProvider.builder()
 *         .addSpanProcessor(BatchSpanProcessor.builder(
 *             OtlpHttpSpanExporter.builder()
 *                 .setEndpoint("https://collector.example.com/v1/traces")
 *                 .build()
 *         ).build())
 *         .build())
 *     // ... logger and meter providers
 *     .build()
 *
 * AppHealth.init(this, otelSdk)
 * ```
 *
 * ## Configuration
 *
 * ```kotlin
 * AppHealth.init(context, otelSdk) {
 *     anrDetection = false      // Disable ANR detection
 *     ndkCrashHandling = false  // Disable native crash handling
 * }
 * ```
 *
 * ## Session Management
 *
 * AppHealth automatically adds a `session.id` attribute to all spans and logs.
 * Sessions rotate on cold start or when the app returns to foreground after 30 minutes
 * in the background.
 */
@OptIn(
    kotlinx.coroutines.DelicateCoroutinesApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class
)
object AppHealth {

    private const val INSTRUMENTATION_NAME = "com.simplecityapps.apphealth.android"
    private const val INSTRUMENTATION_VERSION = "1.0.0"

    @Volatile private var initCalled = false
    @Volatile private var _readyLatch: java.util.concurrent.CountDownLatch? = null

    @Volatile private var _openTelemetry: OpenTelemetry? = null
    @Volatile private var _tracer: Tracer? = null
    @Volatile private var _logger: Logger? = null
    @Volatile private var _meter: Meter? = null
    @Volatile private var sessionManager: SessionManager? = null
    @Volatile private var startupTracer: StartupTracer? = null
    @Volatile private var navigationTracker: NavigationTracker? = null
    @Volatile private var ndkCrashHandler: NdkCrashHandler? = null
    @Volatile private var appHealthConfig: AppHealthConfig? = null

    private val appHealthDispatcher = newSingleThreadContext("apphealth")
    private val appHealthScope = CoroutineScope(SupervisorJob() + appHealthDispatcher)

    /**
     * Returns true if [init] has been called.
     *
     * Note: This does NOT mean the SDK is fully ready. Initialization happens
     * asynchronously. Use [awaitReady] to block until complete, or check [isReady].
     */
    val isInitialized: Boolean
        get() = initCalled

    /**
     * Returns true if the SDK has completed async initialization.
     */
    val isReady: Boolean
        get() = _tracer != null

    /**
     * Initialize the AppHealth SDK.
     *
     * Returns immediately; initialization continues asynchronously.
     * All collectors are enabled by default.
     *
     * @param context Application context
     * @param openTelemetry The OpenTelemetry SDK instance configured by the app
     * @param configure Optional DSL block to customize which collectors are enabled
     */
    fun init(
        context: Context,
        openTelemetry: OpenTelemetry,
        configure: AppHealthConfig.() -> Unit = {}
    ) {
        if (initCalled) return
        initCalled = true

        val latch = java.util.concurrent.CountDownLatch(1)
        _readyLatch = latch

        val userConfig = AppHealthConfig().apply(configure)
        appHealthConfig = userConfig

        val appContext = context.applicationContext

        appHealthScope.launch {
            try {
                _openTelemetry = openTelemetry

                val newSessionManager = SessionManager(appContext)
                sessionManager = newSessionManager

                // Get tracer/logger/meter from app-provided OpenTelemetry
                // Wrap with session-aware decorators to automatically add session.id
                val baseTracer = openTelemetry.getTracer(INSTRUMENTATION_NAME, INSTRUMENTATION_VERSION)
                val baseLogger = openTelemetry.logsBridge.loggerBuilder(INSTRUMENTATION_NAME).build()
                val meter = openTelemetry.getMeter(INSTRUMENTATION_NAME)

                val sessionAwareTracer = SessionAwareTracer(baseTracer) { newSessionManager.sessionId }
                val sessionAwareLogger = SessionAwareLogger(baseLogger) { newSessionManager.sessionId }

                _tracer = sessionAwareTracer
                _logger = sessionAwareLogger
                _meter = meter

                initializeCollectors(
                    appContext as Application,
                    sessionAwareTracer,
                    sessionAwareLogger,
                    meter,
                    openTelemetry,
                    newSessionManager,
                    userConfig
                )
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to initialize AppHealth", e)
            } finally {
                latch.countDown()
            }
        }
    }

    /**
     * Blocks until SDK initialization is complete or timeout is reached.
     *
     * @param timeoutMs Maximum time to wait in milliseconds
     * @return true if SDK is ready, false if timeout elapsed
     */
    fun awaitReady(timeoutMs: Long = 5000): Boolean {
        val latch = _readyLatch ?: return isReady
        return latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    private fun initializeCollectors(
        application: Application,
        tracer: Tracer,
        logger: Logger,
        meter: Meter,
        openTelemetry: OpenTelemetry,
        sessionManager: SessionManager,
        userConfig: AppHealthConfig
    ) {
        // 1. JVM Crash Handler
        if (userConfig.crashHandling) {
            CrashHandler.install(logger)
        }

        // 2. Coroutine Exception Handler
        if (userConfig.coroutineCrashHandling) {
            AppHealthCoroutineExceptionHandler.getInstance().setLogger(logger)
        }

        // 3. ANR Watchdog
        if (userConfig.anrDetection) {
            val anrWatchdog = AnrWatchdog(logger)
            anrWatchdog.start()
            AnrWatchdog.checkHistoricalAnrs(application, logger)
        }

        // 4. Startup Tracer
        if (userConfig.startupTracking) {
            val newStartupTracer = StartupTracer(tracer)
            startupTracer = newStartupTracer
            application.registerActivityLifecycleCallbacks(newStartupTracer)
        }

        // 5. Lifecycle Tracker
        if (userConfig.lifecycleTracking) {
            LifecycleTracker.register(logger, sessionManager, openTelemetry)
        }

        // 6. Jank Tracker
        if (userConfig.jankTracking) {
            val jankTracker = JankTracker(meter, appHealthScope)
            application.registerActivityLifecycleCallbacks(jankTracker)
            jankTracker.start()
        }

        // 7. Navigation Tracker (always created, requires manual setup via trackNavigation)
        navigationTracker = NavigationTracker(tracer)

        // 8. NDK Crash Handler
        if (userConfig.ndkCrashHandling) {
            try {
                val handler = NdkCrashHandler(application, logger)
                handler.initialize()
                ndkCrashHandler = handler
            } catch (e: UnsatisfiedLinkError) {
                android.util.Log.w(TAG, "Native library not available, NDK crash handling disabled", e)
            }
        }
    }

    /**
     * Report that the app is fully drawn and interactive.
     *
     * Call this from your main activity once all critical content is visible.
     * This completes the Time to Full Display (TTFD) measurement.
     */
    fun reportFullyDrawn() {
        startupTracer?.reportFullyDrawn()
    }

    /**
     * Track navigation events from a Jetpack Compose NavController.
     *
     * Call this from a LaunchedEffect in your NavHost composable:
     * ```kotlin
     * val navController = rememberNavController()
     * LaunchedEffect(navController) {
     *     AppHealth.trackNavigation(navController)
     * }
     * ```
     *
     * Routes are automatically normalized (IDs and UUIDs replaced with placeholders).
     *
     * @param navController The NavController to observe
     */
    suspend fun trackNavigation(navController: NavController) {
        navigationTracker?.track(navController)
    }

    /**
     * Get an OkHttp interceptor that traces HTTP requests.
     *
     * ```kotlin
     * val client = OkHttpClient.Builder()
     *     .addInterceptor(AppHealth.okHttpInterceptor())
     *     .build()
     * ```
     *
     * URLs are automatically sanitized (IDs, UUIDs, query params stripped).
     * Configure a custom sanitizer via [AppHealthConfig.urlSanitizer].
     *
     * @return An OkHttp [Interceptor] for network tracing
     */
    fun okHttpInterceptor(): Interceptor {
        return NetworkInterceptor(
            urlSanitizerProvider = { appHealthConfig?.urlSanitizer },
            samplingConfigProvider = { appHealthConfig?.networkSampling },
            tracerProvider = { _tracer }
        )
    }

    /**
     * Triggers a native crash for testing purposes.
     *
     * **WARNING: This will crash the process immediately.**
     *
     * Only use in E2E tests with proper test orchestration (e.g., AndroidX Test Orchestrator).
     * The crash will be captured and reported on the next app launch.
     *
     * @throws IllegalStateException if NDK crash handler is not initialized
     */
    fun triggerNativeCrashForTesting() {
        ndkCrashHandler?.triggerTestCrash()
            ?: throw IllegalStateException("NDK crash handler not initialized")
    }

    // Internal accessors for testing
    internal fun getTracer(): Tracer? = _tracer
    internal fun getLogger(): Logger? = _logger
    internal fun getMeter(): Meter? = _meter
    internal fun getSessionManager(): SessionManager? = sessionManager
    internal fun getAppHealthScope(): CoroutineScope = appHealthScope
    internal fun setStartupTracer(tracer: StartupTracer?) { startupTracer = tracer }
    internal fun getStartupTracer(): StartupTracer? = startupTracer
    internal fun setNavigationTracker(tracker: NavigationTracker?) { navigationTracker = tracker }
    internal fun getNavigationTracker(): NavigationTracker? = navigationTracker

    /**
     * Forces all pending telemetry to be exported.
     * Useful in E2E tests to ensure telemetry reaches the collector.
     *
     * Note: This attempts to flush via SDK types. If the provided OpenTelemetry
     * is not an SDK instance (e.g., noop), flush is a no-op but still returns true.
     *
     * @return true if SDK is initialized, false otherwise
     */
    fun forceFlush(): Boolean {
        val otel = _openTelemetry ?: return false
        val sdk = otel as? OpenTelemetrySdk

        if (sdk != null) {
            runCatching {
                sdk.sdkTracerProvider.forceFlush().join(2, TimeUnit.SECONDS)
                sdk.sdkLoggerProvider.forceFlush().join(2, TimeUnit.SECONDS)
            }
        }

        return true
    }

    /**
     * Resets the SDK state. Intended for testing only.
     *
     * Note: This does NOT shut down the OpenTelemetry SDK - that's the app's responsibility.
     * AppHealth only stops its own collectors.
     */
    @androidx.annotation.VisibleForTesting
    fun reset() {
        // Don't shutdown OpenTelemetry - app owns it
        _openTelemetry = null
        _tracer = null
        _logger = null
        _meter = null
        sessionManager = null
        startupTracer = null
        navigationTracker = null
        ndkCrashHandler = null
        appHealthConfig = null
        _readyLatch = null
        initCalled = false
    }

    private const val TAG = "AppHealth"
}
