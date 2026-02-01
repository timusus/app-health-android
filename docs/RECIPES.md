# Recipes

This document provides implementation patterns for common telemetry features that are not included in the App Health SDK's core functionality.

The SDK focuses on crash handling (JVM, coroutine, NDK), ANR detection, and session management. For other telemetry needs, use these recipes with your OpenTelemetry SDK directly.

## Network Tracing

Use the official `opentelemetry-okhttp-3.0` instrumentation library for HTTP client tracing.

### Setup

```kotlin
dependencies {
    implementation("io.opentelemetry.instrumentation:opentelemetry-okhttp-3.0:2.11.0-alpha")
}
```

```kotlin
val okHttpClient = OkHttpClient.Builder()
    .addInterceptor(OkHttpTracing.create(openTelemetry).newInterceptor())
    .build()
```

### URL Sanitization

To reduce cardinality and protect sensitive data, sanitize URLs before they become span attributes:

```kotlin
object UrlSanitizer {
    private val UUID = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    private val NUMERIC_ID = Regex("/[0-9]+(?=/|$)")

    fun sanitize(url: String): String = url
        .substringBefore("?")           // Strip query params
        .replace(UUID, "{id}")          // Replace UUIDs
        .replace(NUMERIC_ID, "/{id}")   // Replace numeric IDs
}
```

Configure via span processor or custom interceptor.

## Navigation Tracking

Track screen views with Jetpack Navigation Compose:

```kotlin
@Composable
fun TrackNavigation(navController: NavHostController, tracer: Tracer) {
    var currentSpan by remember { mutableStateOf<Span?>(null) }

    LaunchedEffect(navController) {
        navController.currentBackStackEntryFlow.collect { entry ->
            val route = entry.destination.route ?: return@collect

            currentSpan?.end()
            currentSpan = tracer.spanBuilder("screen.view")
                .setAttribute("screen.name", normalizeRoute(route))
                .startSpan()
        }
    }

    DisposableEffect(Unit) {
        onDispose { currentSpan?.end() }
    }
}

private fun normalizeRoute(route: String): String {
    val UUID = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    val NUMERIC = Regex("(?<=/)\\d+(?=/|$)")
    return route.replace(UUID, "{id}").replace(NUMERIC, "{id}")
}
```

## Lifecycle Tracking

Track app foreground/background transitions:

```kotlin
class AppLifecycleTracker(
    private val logger: Logger
) : DefaultLifecycleObserver {

    private var foregroundStartTime = 0L

    override fun onStart(owner: LifecycleOwner) {
        foregroundStartTime = System.currentTimeMillis()
        logger.logRecordBuilder()
            .setSeverity(Severity.INFO)
            .setBody("app.foreground")
            .emit()
    }

    override fun onStop(owner: LifecycleOwner) {
        val duration = System.currentTimeMillis() - foregroundStartTime
        logger.logRecordBuilder()
            .setSeverity(Severity.INFO)
            .setBody("app.background")
            .setAllAttributes(Attributes.of(
                AttributeKey.longKey("app.foreground.duration_ms"), duration
            ))
            .emit()
    }
}

// Register in Application.onCreate()
ProcessLifecycleOwner.get().lifecycle.addObserver(AppLifecycleTracker(logger))
```

## Frame Metrics (Jank Tracking)

Track slow and frozen frames using AndroidX JankStats:

```kotlin
class FrameMetricsTracker(
    private val meter: Meter
) : Application.ActivityLifecycleCallbacks {

    private val totalFrames = meter.counterBuilder("frames.total").build()
    private val slowFrames = meter.counterBuilder("frames.slow").build()
    private val frozenFrames = meter.counterBuilder("frames.frozen").build()
    private var jankStats: JankStats? = null

    private val listener = JankStats.OnFrameListener { frameData ->
        val durationMs = frameData.frameDurationUiNanos / 1_000_000
        totalFrames.add(1)
        if (durationMs > 16) slowFrames.add(1)
        if (durationMs > 700) frozenFrames.add(1)
    }

    override fun onActivityResumed(activity: Activity) {
        jankStats = JankStats.createAndTrack(activity.window, listener)
    }

    override fun onActivityPaused(activity: Activity) {
        jankStats?.isTrackingEnabled = false
        jankStats = null
    }

    // Empty implementations for other callbacks
    override fun onActivityCreated(activity: Activity, state: Bundle?) {}
    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}

// Register in Application.onCreate()
registerActivityLifecycleCallbacks(FrameMetricsTracker(meter))
```

Requires dependency:
```kotlin
implementation("androidx.metrics:metrics-performance:1.0.0-beta01")
```

## Startup Timing

Measure cold start time (TTID - Time To Initial Display):

```kotlin
class StartupTracker(
    private val tracer: Tracer,
    private val processStartTime: Long = Process.getStartElapsedRealtime()
) : Application.ActivityLifecycleCallbacks {

    private val recorded = AtomicBoolean(false)

    override fun onActivityResumed(activity: Activity) {
        if (recorded.compareAndSet(false, true)) {
            val durationMs = SystemClock.elapsedRealtime() - processStartTime
            tracer.spanBuilder("app.startup")
                .setAttribute("startup.type", "cold")
                .setAttribute("startup.duration_ms", durationMs)
                .startSpan()
                .end()
        }
    }

    // Empty implementations for other callbacks
    override fun onActivityCreated(activity: Activity, state: Bundle?) {}
    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}

// Register in Application.onCreate()
registerActivityLifecycleCallbacks(StartupTracker(tracer))
```

For TTFD (Time To Fully Drawn), call `Activity.reportFullyDrawn()` and record a span at that point.
