# Recipes

Implementation patterns for common telemetry features not included in the App Health SDK.

The SDK focuses on crash handling and ANR detection. For other telemetry, use these patterns with your OpenTelemetry SDK directly.

## Network Tracing

Use the official OpenTelemetry OkHttp instrumentation:

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

The default instrumentation includes full URLs which may contain sensitive data or high-cardinality IDs. To sanitize, add a custom `SpanProcessor`:

```kotlin
class UrlSanitizingSpanProcessor : SpanProcessor {
    override fun onStart(parentContext: Context, span: ReadWriteSpan) {
        val url = span.getAttribute(SemanticAttributes.URL_FULL) ?: return
        span.setAttribute(SemanticAttributes.URL_FULL, sanitize(url))
    }

    private fun sanitize(url: String): String = url
        .substringBefore("?")
        .replace(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"), "{id}")
        .replace(Regex("/[0-9]+(?=/|$)"), "/{id}")

    override fun isStartRequired() = true
    override fun onEnd(span: ReadableSpan) {}
    override fun isEndRequired() = false
    override fun shutdown() = CompletableResultCode.ofSuccess()
    override fun forceFlush() = CompletableResultCode.ofSuccess()
}

// Add to your tracer provider
SdkTracerProvider.builder()
    .addSpanProcessor(UrlSanitizingSpanProcessor())
    .addSpanProcessor(BatchSpanProcessor.builder(exporter).build())
    .build()
```

## Navigation Tracking

Track screen views as events (simpler) or spans (if you need time-on-screen).

### Using Events (Recommended)

```kotlin
@Composable
fun TrackNavigation(navController: NavHostController, logger: Logger) {
    LaunchedEffect(navController) {
        navController.currentBackStackEntryFlow.collect { entry ->
            val route = entry.destination.route ?: return@collect
            logger.logRecordBuilder()
                .setSeverity(Severity.INFO)
                .setBody("screen.view")
                .setAllAttributes(Attributes.of(
                    AttributeKey.stringKey("screen.name"), normalizeRoute(route)
                ))
                .emit()
        }
    }
}

private fun normalizeRoute(route: String): String = route
    .replace(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"), "{id}")
    .replace(Regex("(?<=/)\\d+(?=/|$)"), "{id}")
```

### Using Spans (For Time-on-Screen)

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
    private var currentActivity: Activity? = null

    private val listener = JankStats.OnFrameListener { frameData ->
        val durationMs = frameData.frameDurationUiNanos / 1_000_000
        val screenName = currentActivity?.javaClass?.simpleName ?: "unknown"
        val attrs = Attributes.of(AttributeKey.stringKey("screen.name"), screenName)

        totalFrames.add(1, attrs)
        if (durationMs > 16) slowFrames.add(1, attrs)
        if (durationMs > 700) frozenFrames.add(1, attrs)
    }

    override fun onActivityResumed(activity: Activity) {
        currentActivity = activity
        jankStats = JankStats.createAndTrack(activity.window, listener)
    }

    override fun onActivityPaused(activity: Activity) {
        jankStats?.isTrackingEnabled = false
        jankStats = null
    }

    override fun onActivityCreated(activity: Activity, state: Bundle?) {}
    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}

// Register in Application.onCreate()
registerActivityLifecycleCallbacks(FrameMetricsTracker(meter))
```

Requires:
```kotlin
implementation("androidx.metrics:metrics-performance:1.0.0-beta01")
```

## Startup Timing

Measure cold start time (TTID - Time To Initial Display):

```kotlin
class StartupTracker(
    private val logger: Logger,
    private val processStartTime: Long = Process.getStartElapsedRealtime()
) : Application.ActivityLifecycleCallbacks {

    private val recorded = AtomicBoolean(false)

    override fun onActivityResumed(activity: Activity) {
        if (recorded.compareAndSet(false, true)) {
            val durationMs = SystemClock.elapsedRealtime() - processStartTime
            logger.logRecordBuilder()
                .setSeverity(Severity.INFO)
                .setBody("app.startup")
                .setAllAttributes(Attributes.of(
                    AttributeKey.stringKey("startup.type"), "cold",
                    AttributeKey.longKey("startup.duration_ms"), durationMs
                ))
                .emit()
        }
    }

    override fun onActivityCreated(activity: Activity, state: Bundle?) {}
    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}

// Register in Application.onCreate()
registerActivityLifecycleCallbacks(StartupTracker(logger))
```

For TTFD (Time To Fully Drawn), emit another event when your main content is ready.
