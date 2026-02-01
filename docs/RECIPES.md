# Recipes

Implementation patterns for common telemetry features not included in the App Health SDK.

The SDK focuses on crash handling and ANR detection. For other telemetry, use these patterns with your OpenTelemetry SDK directly.

## Network Tracing

Use the official OpenTelemetry OkHttp instrumentation:

```kotlin
dependencies {
    // Alpha status is expected - instrumentations remain alpha until semantic conventions stabilize
    implementation("io.opentelemetry.instrumentation:opentelemetry-okhttp-3.0:2.11.0-alpha")
}
```

```kotlin
val okHttpClient = OkHttpClient.Builder().build()

val tracingClient = OkHttpTelemetry.builder(openTelemetry)
    .build()
    .newCallFactory(okHttpClient)

// Use tracingClient for requests
```

### With Retrofit

```kotlin
val retrofit = Retrofit.Builder()
    .baseUrl("https://api.example.com")
    .callFactory(
        OkHttpTelemetry.builder(openTelemetry)
            .build()
            .newCallFactory(okHttpClient)
    )
    .build()
```

### URL Sanitization

The default instrumentation includes full URLs which may contain sensitive data or high-cardinality IDs. Use a custom `AttributesExtractor` to sanitize at instrumentation time:

```kotlin
class SanitizingUrlAttributesExtractor : AttributesExtractor<Request, Response> {
    override fun onStart(attributes: AttributesBuilder, parentContext: Context, request: Request) {
        val sanitizedUrl = sanitize(request.url.toString())
        attributes.put(UrlAttributes.URL_FULL, sanitizedUrl)
    }

    override fun onEnd(
        attributes: AttributesBuilder,
        context: Context,
        request: Request,
        response: Response?,
        error: Throwable?
    ) {}

    private fun sanitize(url: String): String = url
        .substringBefore("?")
        .replace(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"), "{id}")
        .replace(Regex("/[0-9]+(?=/|$)"), "/{id}")
        .replace(Regex("(?<=/)[A-Za-z0-9_-]{20,}(?=/|$)"), "{token}")
}

// Apply to OkHttpTelemetry
OkHttpTelemetry.builder(openTelemetry)
    .addAttributesExtractor(SanitizingUrlAttributesExtractor())
    .build()
    .newCallFactory(okHttpClient)
```

> **Note:** HTTP semantic conventions migrated from `http.url` to `url.full`. Set `OTEL_SEMCONV_STABILITY_OPT_IN=http` to use only stable conventions.

## Navigation Tracking

Track screen views as events (simpler) or spans (if you need time-on-screen).

### Using Events (Recommended)

For Jetpack Compose Navigation:

```kotlin
@Composable
fun TrackNavigation(navController: NavHostController, logger: Logger) {
    DisposableEffect(navController) {
        val listener = NavController.OnDestinationChangedListener { _, destination, _ ->
            val route = destination.route ?: return@OnDestinationChangedListener
            logger.logRecordBuilder()
                .setSeverity(Severity.INFO)
                .setBody("screen.view")
                .setAllAttributes(Attributes.of(
                    AttributeKey.stringKey("app.screen.name"), normalizeRoute(route),
                    AttributeKey.stringKey("app.screen.id"), route.substringBefore("/")
                ))
                .emit()
        }
        navController.addOnDestinationChangedListener(listener)
        onDispose { navController.removeOnDestinationChangedListener(listener) }
    }
}

private fun normalizeRoute(route: String): String = route
    .substringBefore("?")
    .replace(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"), "{id}")
    .replace(Regex("(?<=/)\\d+(?=/|$)"), "{id}")
```

For Activity-based apps:

```kotlin
class ActivityScreenTracker(
    private val logger: Logger
) : Application.ActivityLifecycleCallbacks {

    override fun onActivityResumed(activity: Activity) {
        logger.logRecordBuilder()
            .setSeverity(Severity.INFO)
            .setBody("screen.view")
            .setAllAttributes(Attributes.of(
                AttributeKey.stringKey("app.screen.name"), activity.javaClass.simpleName
            ))
            .emit()
    }

    override fun onActivityCreated(activity: Activity, state: Bundle?) {}
    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}

// Register in Application.onCreate()
registerActivityLifecycleCallbacks(ActivityScreenTracker(logger))
```

### Using Spans (For Time-on-Screen)

Spans capture duration, useful for engagement analysis. Must handle app backgrounding:

```kotlin
@Composable
fun TrackNavigationWithDuration(
    navController: NavHostController,
    tracer: Tracer,
    lifecycle: LifecycleOwner = LocalLifecycleOwner.current
) {
    var currentSpan by remember { mutableStateOf<Span?>(null) }
    var currentRoute by remember { mutableStateOf<String?>(null) }

    // End span when app backgrounds
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    currentSpan?.end()
                    currentSpan = null
                }
                Lifecycle.Event.ON_START -> {
                    currentRoute?.let { route ->
                        currentSpan = tracer.spanBuilder("screen.view")
                            .setAttribute("app.screen.name", normalizeRoute(route))
                            .startSpan()
                    }
                }
                else -> {}
            }
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }

    // Handle navigation changes
    LaunchedEffect(navController) {
        navController.currentBackStackEntryFlow.collect { entry ->
            val route = entry.destination.route ?: return@collect

            currentSpan?.end()
            currentRoute = route
            currentSpan = tracer.spanBuilder("screen.view")
                .setAttribute("app.screen.name", normalizeRoute(route))
                .startSpan()
        }
    }

    DisposableEffect(Unit) {
        onDispose { currentSpan?.end() }
    }
}
```

## Lifecycle Tracking

Track app foreground/background transitions using semantic conventions:

```kotlin
class AppLifecycleTracker(
    private val logger: Logger
) : DefaultLifecycleObserver {

    private var foregroundStartTime = 0L

    override fun onStart(owner: LifecycleOwner) {
        foregroundStartTime = SystemClock.elapsedRealtime()
        logger.logRecordBuilder()
            .setSeverity(Severity.INFO)
            .setBody("device.app.lifecycle")
            .setAllAttributes(Attributes.of(
                AttributeKey.stringKey("android.app.state"), "foreground"
            ))
            .emit()
    }

    override fun onStop(owner: LifecycleOwner) {
        val durationMs = SystemClock.elapsedRealtime() - foregroundStartTime
        logger.logRecordBuilder()
            .setSeverity(Severity.INFO)
            .setBody("device.app.lifecycle")
            .setAllAttributes(Attributes.of(
                AttributeKey.stringKey("android.app.state"), "background",
                AttributeKey.longKey("app.foreground.duration_ms"), durationMs
            ))
            .emit()
    }
}

// Register in Application.onCreate()
ProcessLifecycleOwner.get().lifecycle.addObserver(AppLifecycleTracker(logger))
```

> **Note:** Use `SystemClock.elapsedRealtime()` for duration, not `System.currentTimeMillis()` which can jump when the system clock changes.

## Frame Metrics (Jank Tracking)

Track slow and frozen frames using AndroidX JankStats:

```kotlin
dependencies {
    implementation("androidx.metrics:metrics-performance:1.0.0")
}
```

```kotlin
class FrameMetricsTracker(
    private val meter: Meter
) : Application.ActivityLifecycleCallbacks {

    private val frameDuration = meter.histogramBuilder("frames.duration")
        .setDescription("Frame render duration")
        .setUnit("ms")
        .build()
    private val totalFrames = meter.counterBuilder("frames.total").build()
    private val jankFrames = meter.counterBuilder("frames.jank").build()
    private val frozenFrames = meter.counterBuilder("frames.frozen").build()

    private var jankStats: JankStats? = null
    private var currentActivity: Activity? = null

    private val listener = JankStats.OnFrameListener { frameData ->
        val durationMs = frameData.frameDurationUiNanos / 1_000_000.0
        val screenName = currentActivity?.javaClass?.simpleName ?: "unknown"

        // Extract state for attribution (if using PerformanceMetricsState)
        val screen = frameData.states.find { it.key == "screen.name" }?.value ?: screenName
        val attrs = Attributes.of(AttributeKey.stringKey("screen.name"), screen)

        frameDuration.record(durationMs, attrs)
        totalFrames.add(1, attrs)

        // Use JankStats' adaptive detection (respects device refresh rate)
        if (frameData.isJank) jankFrames.add(1, attrs)
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

### State Attribution for Compose

Track current screen for better jank attribution:

```kotlin
@Composable
fun TrackScreenForMetrics(screenName: String) {
    val view = LocalView.current
    val holder = remember(view) { PerformanceMetricsState.getHolderForHierarchy(view) }

    DisposableEffect(screenName) {
        holder.state?.putState("screen.name", screenName)
        onDispose { holder.state?.removeState("screen.name") }
    }
}
```

> **Note:** `frameData.isJank` adapts to the device's refresh rate (60Hz/90Hz/120Hz). Avoid hardcoded thresholds like 16ms.

## Startup Timing

Measure cold start time using spans for duration tracking:

```kotlin
class StartupTracker(
    private val tracer: Tracer,
    private val logger: Logger,
    private val processStartTime: Long = Process.getStartElapsedRealtime()
) : Application.ActivityLifecycleCallbacks {

    private var startupSpan: Span? = null
    private var initialActivity: String? = null
    private val startupCompleted = AtomicBoolean(false)

    fun start() {
        val now = SystemClock.elapsedRealtime()
        // Skip if too long since process start (background/service start)
        if (now - processStartTime > 60_000L) return

        startupSpan = tracer.spanBuilder("AppStart")
            .setStartTimestamp(processStartTime, TimeUnit.MILLISECONDS)
            .setAttribute("start.type", "cold")
            .startSpan()
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        startupSpan?.addEvent("activity.created", Attributes.of(
            AttributeKey.stringKey("activity.name"), activity.javaClass.simpleName
        ))
    }

    override fun onActivityResumed(activity: Activity) {
        if (startupCompleted.compareAndSet(false, true)) {
            startupSpan?.let { span ->
                val durationMs = SystemClock.elapsedRealtime() - processStartTime
                span.setAttribute("startup.duration_ms", durationMs)
                span.end()
                initialActivity = activity.javaClass.simpleName
            }
            startupSpan = null
        }
    }

    /**
     * Call when your main content is fully loaded for TTFD tracking.
     * Also calls Activity.reportFullyDrawn() for Android runtime optimization.
     */
    fun reportFullyDrawn(activity: Activity) {
        val ttfd = SystemClock.elapsedRealtime() - processStartTime
        logger.logRecordBuilder()
            .setSeverity(Severity.INFO)
            .setBody("app.fully_drawn")
            .setAllAttributes(Attributes.of(
                AttributeKey.longKey("ttfd_ms"), ttfd,
                AttributeKey.stringKey("activity.name"), activity.javaClass.simpleName
            ))
            .emit()

        activity.reportFullyDrawn()
    }

    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}

// In Application.onCreate()
val startupTracker = StartupTracker(tracer, logger)
startupTracker.start()
registerActivityLifecycleCallbacks(startupTracker)

// In your main Activity when content is ready
startupTracker.reportFullyDrawn(this)
```

> **Note:** TTID (Time To Initial Display) is measured to first `onActivityResumed`. TTFD (Time To Full Display) requires calling `reportFullyDrawn()` when your app is actually usable.
