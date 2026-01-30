# Session Tracking Reference

This document describes the session tracking and device ID implementation that was removed from AppHealth. Use this as a reference for reimplementing elsewhere.

## Session ID

### Concept

A `session.id` (UUID) correlates telemetry events within a single user session. Sessions rotate:
- On cold start (new process)
- When app returns to foreground after 30+ minutes in background

### Implementation

```kotlin
class SessionManager(context: Context) {
    private val prefs = context.getSharedPreferences("telemetry_session", Context.MODE_PRIVATE)
    private val _sessionId = AtomicReference<String>()

    val sessionId: String get() = _sessionId.get()

    init {
        initializeSession()
    }

    private fun initializeSession() {
        val existingId = prefs.getString("session_id", null)
        val lastBackground = prefs.getLong("last_background_time", 0L)
        val now = System.currentTimeMillis()
        val sessionExpired = lastBackground > 0 && (now - lastBackground) > SESSION_TIMEOUT_MS

        val sessionId = if (existingId == null || sessionExpired) {
            UUID.randomUUID().toString().also {
                prefs.edit().putString("session_id", it).apply()
            }
        } else {
            existingId
        }
        _sessionId.set(sessionId)
    }

    fun onForeground() {
        val lastBackground = prefs.getLong("last_background_time", 0L)
        val now = System.currentTimeMillis()

        if (lastBackground > 0 && (now - lastBackground) > SESSION_TIMEOUT_MS) {
            val newId = UUID.randomUUID().toString()
            _sessionId.set(newId)
            prefs.edit().putString("session_id", newId).apply()
        }
    }

    fun onBackground() {
        prefs.edit().putLong("last_background_time", System.currentTimeMillis()).apply()
    }

    companion object {
        private const val SESSION_TIMEOUT_MS = 30 * 60 * 1000L // 30 minutes
    }
}
```

### Integration with ProcessLifecycleOwner

```kotlin
class LifecycleObserver(private val sessionManager: SessionManager) : DefaultLifecycleObserver {
    override fun onStart(owner: LifecycleOwner) {
        sessionManager.onForeground()
    }

    override fun onStop(owner: LifecycleOwner) {
        sessionManager.onBackground()
    }
}

// Register in Application.onCreate()
ProcessLifecycleOwner.get().lifecycle.addObserver(lifecycleObserver)
```

### Adding to OpenTelemetry

To add `session.id` to all spans and logs, use processors:

```kotlin
class SessionSpanProcessor(private val sessionProvider: () -> String) : SpanProcessor {
    override fun onStart(parentContext: Context, span: ReadWriteSpan) {
        span.setAttribute("session.id", sessionProvider())
    }
    override fun isStartRequired() = true
    override fun onEnd(span: ReadableSpan) {}
    override fun isEndRequired() = false
    override fun shutdown() = CompletableResultCode.ofSuccess()
    override fun forceFlush() = CompletableResultCode.ofSuccess()
}

class SessionLogRecordProcessor(private val sessionProvider: () -> String) : LogRecordProcessor {
    override fun onEmit(context: Context, logRecord: ReadWriteLogRecord) {
        logRecord.setAttribute(AttributeKey.stringKey("session.id"), sessionProvider())
    }
    override fun shutdown() = CompletableResultCode.ofSuccess()
    override fun forceFlush() = CompletableResultCode.ofSuccess()
}

// Add to OTel SDK
val sessionManager = SessionManager(context)
val openTelemetry = OpenTelemetrySdk.builder()
    .setTracerProvider(SdkTracerProvider.builder()
        .addSpanProcessor(SessionSpanProcessor { sessionManager.sessionId })
        .addSpanProcessor(BatchSpanProcessor.builder(...).build())
        .build())
    .setLoggerProvider(SdkLoggerProvider.builder()
        .addLogRecordProcessor(SessionLogRecordProcessor { sessionManager.sessionId })
        .addLogRecordProcessor(BatchLogRecordProcessor.builder(...).build())
        .build())
    .build()
```

---

## Installation ID

### Concept

A `device.installation.id` (UUID) persists across app sessions until uninstall. Useful for distinguishing "100 crashes from 1 device" vs "100 crashes from 100 devices".

### Implementation

```kotlin
class InstallationIdManager(context: Context) {
    private val prefs = context.getSharedPreferences("telemetry_session", Context.MODE_PRIVATE)

    val installationId: String = getOrCreate()

    private fun getOrCreate(): String {
        val existing = prefs.getString("installation_id", null)
        if (existing != null) return existing

        val newId = UUID.randomUUID().toString()
        prefs.edit().putString("installation_id", newId).apply()
        return newId
    }
}
```

### Privacy Considerations

- **Not derived from hardware**: No IMEI, SSAID, MAC address, or Advertising ID
- **App-scoped**: Stored in app-private SharedPreferences, not shared across apps
- **Deleted on uninstall**: No persistent tracking
- **Should be opt-out**: Provide `installationIdEnabled = false` config for GDPR compliance

### Adding to OpenTelemetry

Installation ID should be a **Resource attribute** (not span attribute) since it never changes:

```kotlin
val installationId = InstallationIdManager(context).installationId

val resource = Resource.builder()
    .put("device.installation.id", installationId)
    .build()

val openTelemetry = OpenTelemetrySdk.builder()
    .setTracerProvider(SdkTracerProvider.builder()
        .setResource(Resource.getDefault().merge(resource))
        .build())
    .build()
```

---

## Why These Were Removed from AppHealth

AppHealth is now pure instrumentation - it emits telemetry to an app-provided OpenTelemetry SDK without modifying global state. Session/device tracking requires either:

1. **Processors added to the OTel pipeline** (app's responsibility)
2. **A convenience wrapper** that builds OTel with processors included

Both approaches require the app to opt-in explicitly, which is cleaner than magic injection.
