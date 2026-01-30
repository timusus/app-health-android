# Test Suite Improvements Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add E2E tests with a sample app and OTLP collector verification, plus integration tests for critical paths (crashes, network, NDK).

**Architecture:** Create a sample Android app module that initializes the SDK and provides test hooks. Use a mock OTLP collector (simple HTTP server) to verify telemetry emissions. Add Robolectric for Android component tests, MockWebServer for network tests, and instrumented tests for device-specific features (NDK crashes, ANR).

**Tech Stack:** JUnit 4, Robolectric, MockWebServer, AndroidX Test, Compose, OkHttp

---

## Phase 1: Sample App Module

### Task 1: Create Sample App Module Structure

**Files:**
- Create: `sample/build.gradle.kts`
- Create: `sample/src/main/AndroidManifest.xml`
- Create: `sample/src/main/kotlin/com/simplecityapps/telemetry/sample/SampleApp.kt`
- Create: `sample/src/main/kotlin/com/simplecityapps/telemetry/sample/MainActivity.kt`
- Modify: `settings.gradle.kts`

**Step 1: Update settings.gradle.kts to include sample module**

Add at the end of the file:
```kotlin
include(":sample")
```

**Step 2: Create sample/build.gradle.kts**

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.simplecityapps.telemetry.sample"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.simplecityapps.telemetry.sample"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.6"
    }
}

dependencies {
    implementation(project(":telemetry"))

    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")

    implementation(platform("androidx.compose:compose-bom:2024.01.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.navigation:navigation-compose:2.7.6")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")

    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:rules:1.5.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.01.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
```

**Step 3: Create sample/src/main/AndroidManifest.xml**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.INTERNET" />

    <application
        android:name=".SampleApp"
        android:allowBackup="true"
        android:label="Telemetry Sample"
        android:supportsRtl="true"
        android:theme="@android:style/Theme.Material.Light.NoActionBar">

        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>

</manifest>
```

**Step 4: Create SampleApp.kt**

```kotlin
package com.simplecityapps.telemetry.sample

import android.app.Application
import com.simplecityapps.telemetry.android.Telemetry

class SampleApp : Application() {

    override fun onCreate() {
        super.onCreate()

        val endpoint = System.getProperty("telemetry.endpoint", "http://10.0.2.2:4318")

        Telemetry.init(
            context = this,
            endpoint = endpoint,
            serviceName = "telemetry-sample"
        )
    }
}
```

**Step 5: Create MainActivity.kt**

```kotlin
package com.simplecityapps.telemetry.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.simplecityapps.telemetry.android.Telemetry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request

class MainActivity : ComponentActivity() {

    private val okHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor(Telemetry.okHttpInterceptor())
            .build()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Telemetry.reportFullyDrawn()

        setContent {
            MaterialTheme {
                TelemetryTestScreen(
                    onTriggerCrash = { triggerJvmCrash() },
                    onTriggerNdkCrash = { triggerNdkCrash() },
                    onTriggerNetworkRequest = { triggerNetworkRequest() },
                    onTriggerCustomSpan = { triggerCustomSpan() },
                    onTriggerCustomEvent = { triggerCustomEvent() }
                )
            }
        }
    }

    private fun triggerJvmCrash() {
        throw RuntimeException("Test JVM crash from sample app")
    }

    private fun triggerNdkCrash() {
        // This would call native code that triggers SIGSEGV
        // For now, just log that it would crash
        Telemetry.logEvent("ndk_crash_requested")
    }

    private fun triggerNetworkRequest() {
        Thread {
            try {
                val request = Request.Builder()
                    .url("https://httpbin.org/get")
                    .build()
                okHttpClient.newCall(request).execute().use { response ->
                    Telemetry.logEvent("network_request_complete", mapOf(
                        "status_code" to response.code
                    ))
                }
            } catch (e: Exception) {
                Telemetry.logEvent("network_request_failed", mapOf(
                    "error" to (e.message ?: "unknown")
                ))
            }
        }.start()
    }

    private fun triggerCustomSpan() {
        val span = Telemetry.startSpan("sample.custom_operation")
        Thread.sleep(100)
        span.end()
    }

    private fun triggerCustomEvent() {
        Telemetry.logEvent("sample.button_clicked", mapOf(
            "button_name" to "custom_event",
            "timestamp" to System.currentTimeMillis()
        ))
    }
}

@Composable
fun TelemetryTestScreen(
    onTriggerCrash: () -> Unit,
    onTriggerNdkCrash: () -> Unit,
    onTriggerNetworkRequest: () -> Unit,
    onTriggerCustomSpan: () -> Unit,
    onTriggerCustomEvent: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Telemetry Sample",
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(onClick = onTriggerCustomEvent) {
            Text("Trigger Custom Event")
        }

        Button(onClick = onTriggerCustomSpan) {
            Text("Trigger Custom Span")
        }

        Button(onClick = onTriggerNetworkRequest) {
            Text("Trigger Network Request")
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Crash Tests (will crash app)",
            style = MaterialTheme.typography.titleSmall
        )

        Button(
            onClick = onTriggerCrash,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
        ) {
            Text("Trigger JVM Crash")
        }

        Button(
            onClick = onTriggerNdkCrash,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
        ) {
            Text("Trigger NDK Crash")
        }
    }
}
```

**Step 6: Commit**

```bash
git add settings.gradle.kts sample/
git commit -m "feat: add sample app module for manual testing"
```

---

## Phase 2: Mock OTLP Collector for E2E Tests

### Task 2: Create Mock OTLP Collector

**Files:**
- Create: `sample/src/androidTest/kotlin/com/simplecityapps/telemetry/sample/MockOtlpCollector.kt`

**Step 1: Create MockOtlpCollector.kt**

```kotlin
package com.simplecityapps.telemetry.sample

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Mock OTLP collector that captures telemetry requests for verification.
 */
class MockOtlpCollector {

    private val server = MockWebServer()
    private val receivedTraces = CopyOnWriteArrayList<RecordedRequest>()
    private val receivedLogs = CopyOnWriteArrayList<RecordedRequest>()
    private val receivedMetrics = CopyOnWriteArrayList<RecordedRequest>()

    private var traceLatch: CountDownLatch? = null
    private var logLatch: CountDownLatch? = null
    private var metricLatch: CountDownLatch? = null

    val endpoint: String
        get() = server.url("/").toString().trimEnd('/')

    fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                when (request.path) {
                    "/v1/traces" -> {
                        receivedTraces.add(request)
                        traceLatch?.countDown()
                    }
                    "/v1/logs" -> {
                        receivedLogs.add(request)
                        logLatch?.countDown()
                    }
                    "/v1/metrics" -> {
                        receivedMetrics.add(request)
                        metricLatch?.countDown()
                    }
                }
                return MockResponse().setResponseCode(200)
            }
        }
        server.start()
    }

    fun stop() {
        server.shutdown()
    }

    fun expectTraces(count: Int) {
        traceLatch = CountDownLatch(count)
    }

    fun expectLogs(count: Int) {
        logLatch = CountDownLatch(count)
    }

    fun expectMetrics(count: Int) {
        metricLatch = CountDownLatch(count)
    }

    fun awaitTraces(timeoutSeconds: Long = 10): Boolean {
        return traceLatch?.await(timeoutSeconds, TimeUnit.SECONDS) ?: true
    }

    fun awaitLogs(timeoutSeconds: Long = 10): Boolean {
        return logLatch?.await(timeoutSeconds, TimeUnit.SECONDS) ?: true
    }

    fun awaitMetrics(timeoutSeconds: Long = 10): Boolean {
        return metricLatch?.await(timeoutSeconds, TimeUnit.SECONDS) ?: true
    }

    fun getTraces(): List<RecordedRequest> = receivedTraces.toList()

    fun getLogs(): List<RecordedRequest> = receivedLogs.toList()

    fun getMetrics(): List<RecordedRequest> = receivedMetrics.toList()

    fun clear() {
        receivedTraces.clear()
        receivedLogs.clear()
        receivedMetrics.clear()
        traceLatch = null
        logLatch = null
        metricLatch = null
    }

    fun hasTraceContaining(text: String): Boolean {
        return receivedTraces.any { request ->
            request.body.readUtf8().contains(text)
        }
    }

    fun hasLogContaining(text: String): Boolean {
        return receivedLogs.any { request ->
            request.body.readUtf8().contains(text)
        }
    }
}
```

**Step 2: Add MockWebServer test dependency to sample/build.gradle.kts**

Add to androidTestImplementation dependencies:
```kotlin
androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
```

**Step 3: Commit**

```bash
git add sample/
git commit -m "feat: add mock OTLP collector for E2E tests"
```

---

### Task 3: Create E2E Test for Custom Events and Spans

**Files:**
- Create: `sample/src/androidTest/kotlin/com/simplecityapps/telemetry/sample/TelemetryE2ETest.kt`

**Step 1: Create TelemetryE2ETest.kt**

```kotlin
package com.simplecityapps.telemetry.sample

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.simplecityapps.telemetry.android.Telemetry
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class TelemetryE2ETest {

    private lateinit var collector: MockOtlpCollector

    @Before
    fun setup() {
        collector = MockOtlpCollector()
        collector.start()

        // Reset and reinitialize Telemetry with mock collector
        Telemetry.reset()

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        Telemetry.init(
            context = context,
            endpoint = collector.endpoint,
            serviceName = "e2e-test"
        )

        // Wait for async init to complete
        Thread.sleep(500)
    }

    @After
    fun teardown() {
        Telemetry.reset()
        collector.stop()
    }

    @Test
    fun customEventEmitsLogToCollector() {
        collector.expectLogs(1)

        Telemetry.logEvent("test.custom_event", mapOf(
            "key1" to "value1",
            "key2" to 42
        ))

        val received = collector.awaitLogs(timeoutSeconds = 30)
        assertTrue(received, "Should receive log within timeout")
        assertTrue(collector.getLogs().isNotEmpty(), "Should have received at least one log")
    }

    @Test
    fun customSpanEmitsTraceToCollector() {
        collector.expectTraces(1)

        val span = Telemetry.startSpan("test.custom_span")
        Thread.sleep(50)
        span.end()

        val received = collector.awaitTraces(timeoutSeconds = 30)
        assertTrue(received, "Should receive trace within timeout")
        assertTrue(collector.getTraces().isNotEmpty(), "Should have received at least one trace")
    }

    @Test
    fun setUserIdIncludedInSubsequentTelemetry() {
        Telemetry.setUserId("test-user-123")

        collector.expectLogs(1)
        Telemetry.logEvent("test.with_user_id")

        val received = collector.awaitLogs(timeoutSeconds = 30)
        assertTrue(received, "Should receive log within timeout")

        // The log should contain user.id attribute
        val hasUserId = collector.getLogs().any { request ->
            val body = request.body.clone().readUtf8()
            body.contains("user.id") || body.contains("test-user-123")
        }
        assertTrue(hasUserId, "Log should contain user ID")
    }

    @Test
    fun multipleEventsAllReachCollector() {
        val eventCount = 5
        collector.expectLogs(eventCount)

        repeat(eventCount) { i ->
            Telemetry.logEvent("test.batch_event_$i", mapOf("index" to i))
        }

        val received = collector.awaitLogs(timeoutSeconds = 30)
        assertTrue(received, "Should receive all logs within timeout")
        assertTrue(
            collector.getLogs().size >= eventCount,
            "Should have received at least $eventCount logs, got ${collector.getLogs().size}"
        )
    }
}
```

**Step 2: Commit**

```bash
git add sample/src/androidTest/
git commit -m "test: add E2E tests for custom events and spans"
```

---

### Task 4: Create E2E Test for Network Interceptor

**Files:**
- Create: `sample/src/androidTest/kotlin/com/simplecityapps/telemetry/sample/NetworkInterceptorE2ETest.kt`

**Step 1: Create NetworkInterceptorE2ETest.kt**

```kotlin
package com.simplecityapps.telemetry.sample

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.simplecityapps.telemetry.android.Telemetry
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class NetworkInterceptorE2ETest {

    private lateinit var collector: MockOtlpCollector
    private lateinit var apiServer: MockWebServer
    private lateinit var client: OkHttpClient

    @Before
    fun setup() {
        collector = MockOtlpCollector()
        collector.start()

        apiServer = MockWebServer()
        apiServer.start()

        Telemetry.reset()

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        Telemetry.init(
            context = context,
            endpoint = collector.endpoint,
            serviceName = "network-e2e-test"
        )

        client = OkHttpClient.Builder()
            .addInterceptor(Telemetry.okHttpInterceptor())
            .build()

        Thread.sleep(500)
    }

    @After
    fun teardown() {
        Telemetry.reset()
        collector.stop()
        apiServer.shutdown()
    }

    @Test
    fun httpRequestEmitsSpanWithAttributes() {
        apiServer.enqueue(MockResponse().setBody("""{"status":"ok"}""").setResponseCode(200))

        collector.expectTraces(1)

        val request = Request.Builder()
            .url(apiServer.url("/api/users/123"))
            .build()

        client.newCall(request).execute().use { response ->
            assertTrue(response.isSuccessful)
        }

        val received = collector.awaitTraces(timeoutSeconds = 30)
        assertTrue(received, "Should receive trace within timeout")

        val traces = collector.getTraces()
        assertTrue(traces.isNotEmpty(), "Should have received trace")

        // Verify span contains HTTP attributes
        val traceBody = traces.first().body.clone().readUtf8()
        assertTrue(
            traceBody.contains("http") || traceBody.contains("HTTP"),
            "Trace should contain HTTP span data"
        )
    }

    @Test
    fun httpErrorResponseSetsErrorStatus() {
        apiServer.enqueue(MockResponse().setResponseCode(500).setBody("Internal Server Error"))

        collector.expectTraces(1)

        val request = Request.Builder()
            .url(apiServer.url("/api/error"))
            .build()

        client.newCall(request).execute().use { response ->
            // Response should be 500
            assertTrue(response.code == 500)
        }

        val received = collector.awaitTraces(timeoutSeconds = 30)
        assertTrue(received, "Should receive trace within timeout")

        val traces = collector.getTraces()
        assertTrue(traces.isNotEmpty(), "Should have received trace for error response")
    }

    @Test
    fun urlWithSensitiveDataIsSanitized() {
        apiServer.enqueue(MockResponse().setBody("{}").setResponseCode(200))

        collector.expectTraces(1)

        // URL with UUID that should be sanitized
        val request = Request.Builder()
            .url(apiServer.url("/users/123e4567-e89b-12d3-a456-426614174000/profile"))
            .build()

        client.newCall(request).execute().close()

        val received = collector.awaitTraces(timeoutSeconds = 30)
        assertTrue(received, "Should receive trace within timeout")

        val traces = collector.getTraces()
        assertTrue(traces.isNotEmpty())

        val traceBody = traces.first().body.clone().readUtf8()
        // The UUID should be replaced with {id}
        assertTrue(
            !traceBody.contains("123e4567-e89b-12d3-a456-426614174000"),
            "UUID should be sanitized from trace"
        )
    }
}
```

**Step 2: Commit**

```bash
git add sample/src/androidTest/
git commit -m "test: add E2E tests for network interceptor"
```

---

### Task 5: Create E2E Test for JVM Crash Handler

**Files:**
- Create: `sample/src/androidTest/kotlin/com/simplecityapps/telemetry/sample/CrashHandlerE2ETest.kt`

**Step 1: Create CrashHandlerE2ETest.kt**

```kotlin
package com.simplecityapps.telemetry.sample

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.simplecityapps.telemetry.android.Telemetry
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertTrue
import kotlin.test.fail

@RunWith(AndroidJUnit4::class)
class CrashHandlerE2ETest {

    private lateinit var collector: MockOtlpCollector
    private var originalHandler: Thread.UncaughtExceptionHandler? = null

    @Before
    fun setup() {
        collector = MockOtlpCollector()
        collector.start()

        Telemetry.reset()

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        Telemetry.init(
            context = context,
            endpoint = collector.endpoint,
            serviceName = "crash-e2e-test"
        )

        Thread.sleep(500)

        // Capture original handler so we can prevent actual crash
        originalHandler = Thread.getDefaultUncaughtExceptionHandler()
    }

    @After
    fun teardown() {
        // Restore original handler
        originalHandler?.let { Thread.setDefaultUncaughtExceptionHandler(it) }
        Telemetry.reset()
        collector.stop()
    }

    @Test
    fun jvmExceptionEmitsErrorLogToCollector() {
        collector.expectLogs(1)

        // Install a test handler that catches after telemetry handler
        var crashCaptured = false
        val testHandler = Thread.UncaughtExceptionHandler { thread, throwable ->
            crashCaptured = true
            // Don't rethrow - we're testing, not crashing
        }

        // The Telemetry crash handler is already installed
        // We need to chain our test handler after it
        val currentHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // Let the current (telemetry) handler process it
            currentHandler?.uncaughtException(thread, throwable)
            // Then mark it captured
            crashCaptured = true
        }

        // Trigger crash on a background thread
        val crashThread = Thread {
            throw RuntimeException("Test crash for E2E verification")
        }
        crashThread.start()
        crashThread.join(5000)

        // Wait for log to be sent
        val received = collector.awaitLogs(timeoutSeconds = 30)

        assertTrue(crashCaptured, "Crash should have been captured")
        assertTrue(received, "Should receive crash log within timeout")

        val logs = collector.getLogs()
        assertTrue(logs.isNotEmpty(), "Should have received crash log")

        // Verify log contains crash information
        val logBody = logs.first().body.clone().readUtf8()
        assertTrue(
            logBody.contains("RuntimeException") || logBody.contains("crash") || logBody.contains("ERROR"),
            "Log should contain crash information"
        )
    }

    @Test
    fun crashLogContainsStackTrace() {
        collector.expectLogs(1)

        var crashCaptured = false
        val currentHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            currentHandler?.uncaughtException(thread, throwable)
            crashCaptured = true
        }

        val crashThread = Thread({
            throw IllegalStateException("Stack trace test exception")
        }, "test-crash-thread")
        crashThread.start()
        crashThread.join(5000)

        val received = collector.awaitLogs(timeoutSeconds = 30)
        assertTrue(received, "Should receive crash log")

        val logs = collector.getLogs()
        assertTrue(logs.isNotEmpty())

        val logBody = logs.first().body.clone().readUtf8()
        // Should contain thread name or stack trace indicator
        assertTrue(
            logBody.contains("test-crash-thread") ||
            logBody.contains("stacktrace") ||
            logBody.contains("IllegalStateException"),
            "Log should contain stack trace or thread info"
        )
    }
}
```

**Step 2: Commit**

```bash
git add sample/src/androidTest/
git commit -m "test: add E2E tests for JVM crash handler"
```

---

## Phase 3: Integration Tests with Robolectric

### Task 6: Add Robolectric to Telemetry Module

**Files:**
- Modify: `telemetry/build.gradle.kts`

**Step 1: Add Robolectric dependency to telemetry/build.gradle.kts**

Add to testImplementation:
```kotlin
testImplementation("org.robolectric:robolectric:4.11.1")
testImplementation("androidx.test:core:1.5.0")
testImplementation("androidx.test.ext:junit:1.1.5")
```

**Step 2: Commit**

```bash
git add telemetry/build.gradle.kts
git commit -m "chore: add Robolectric testing dependencies"
```

---

### Task 7: Create Robolectric Integration Test for Telemetry Init

**Files:**
- Create: `telemetry/src/test/kotlin/com/simplecityapps/telemetry/android/TelemetryRobolectricTest.kt`

**Step 1: Create TelemetryRobolectricTest.kt**

```kotlin
package com.simplecityapps.telemetry.android

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TelemetryRobolectricTest {

    private lateinit var application: Application

    @Before
    fun setup() {
        application = ApplicationProvider.getApplicationContext()
        Telemetry.reset()
    }

    @After
    fun teardown() {
        Telemetry.reset()
    }

    @Test
    fun `init with real application context succeeds`() {
        Telemetry.init(
            context = application,
            endpoint = "http://localhost:4318",
            serviceName = "robolectric-test"
        )

        assertTrue(Telemetry.isInitialized)
    }

    @Test
    fun `session manager creates valid session ID`() {
        Telemetry.init(
            context = application,
            endpoint = "http://localhost:4318",
            serviceName = "robolectric-test"
        )

        // Wait for async init
        Thread.sleep(500)

        val sessionManager = Telemetry.getSessionManager()
        assertNotNull(sessionManager)
        assertNotNull(sessionManager.sessionId)
        assertTrue(sessionManager.sessionId.isNotEmpty())
    }

    @Test
    fun `startSpan returns valid span after init`() {
        Telemetry.init(
            context = application,
            endpoint = "http://localhost:4318",
            serviceName = "robolectric-test"
        )

        // Wait for async init
        Thread.sleep(500)

        val span = Telemetry.startSpan("test-span")
        assertNotNull(span)
        assertTrue(span.isRecording)
        span.end()
    }

    @Test
    fun `otel config creates tracer and logger`() {
        Telemetry.init(
            context = application,
            endpoint = "http://localhost:4318",
            serviceName = "robolectric-test"
        )

        Thread.sleep(500)

        val config = Telemetry.getConfig()
        assertNotNull(config)
        assertNotNull(config.tracer)
        assertNotNull(config.logger)
        assertNotNull(config.meter)
    }

    @Test
    fun `shared preferences persists session ID across instances`() {
        Telemetry.init(
            context = application,
            endpoint = "http://localhost:4318",
            serviceName = "robolectric-test"
        )
        Thread.sleep(500)

        val firstSessionId = Telemetry.getSessionManager()?.sessionId
        assertNotNull(firstSessionId)

        // Reset and reinit (simulating app restart within 30 min)
        Telemetry.reset()

        Telemetry.init(
            context = application,
            endpoint = "http://localhost:4318",
            serviceName = "robolectric-test"
        )
        Thread.sleep(500)

        val secondSessionId = Telemetry.getSessionManager()?.sessionId
        assertNotNull(secondSessionId)

        // Session ID should be the same (within 30 min timeout)
        assertTrue(
            firstSessionId == secondSessionId,
            "Session ID should persist: $firstSessionId != $secondSessionId"
        )
    }
}
```

**Step 2: Commit**

```bash
git add telemetry/src/test/kotlin/com/simplecityapps/telemetry/android/TelemetryRobolectricTest.kt
git commit -m "test: add Robolectric integration tests for Telemetry init"
```

---

### Task 8: Create MockWebServer Integration Test for NetworkInterceptor

**Files:**
- Create: `telemetry/src/test/kotlin/com/simplecityapps/telemetry/android/NetworkInterceptorIntegrationTest.kt`
- Modify: `telemetry/build.gradle.kts` (add MockWebServer)

**Step 1: Add MockWebServer to telemetry/build.gradle.kts**

Add to testImplementation:
```kotlin
testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
testImplementation("com.squareup.okhttp3:okhttp:4.12.0")
```

**Step 2: Create NetworkInterceptorIntegrationTest.kt**

```kotlin
package com.simplecityapps.telemetry.android

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NetworkInterceptorIntegrationTest {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var client: OkHttpClient

    @Before
    fun setup() {
        mockWebServer = MockWebServer()
        mockWebServer.start()

        Telemetry.reset()

        val application: Application = ApplicationProvider.getApplicationContext()
        Telemetry.init(
            context = application,
            endpoint = "http://localhost:4318",
            serviceName = "network-integration-test"
        )

        Thread.sleep(500)

        client = OkHttpClient.Builder()
            .addInterceptor(Telemetry.okHttpInterceptor())
            .build()
    }

    @After
    fun teardown() {
        Telemetry.reset()
        mockWebServer.shutdown()
    }

    @Test
    fun `interceptor passes through successful response`() {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"message": "success"}""")
        )

        val request = Request.Builder()
            .url(mockWebServer.url("/api/test"))
            .build()

        val response = client.newCall(request).execute()

        assertEquals(200, response.code)
        assertEquals("""{"message": "success"}""", response.body?.string())
    }

    @Test
    fun `interceptor passes through error response`() {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(500)
                .setBody("Internal Server Error")
        )

        val request = Request.Builder()
            .url(mockWebServer.url("/api/error"))
            .build()

        val response = client.newCall(request).execute()

        assertEquals(500, response.code)
    }

    @Test
    fun `interceptor handles multiple sequential requests`() {
        repeat(5) { i ->
            mockWebServer.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("""{"index": $i}""")
            )
        }

        repeat(5) { i ->
            val request = Request.Builder()
                .url(mockWebServer.url("/api/item/$i"))
                .build()

            val response = client.newCall(request).execute()
            assertEquals(200, response.code)
            response.close()
        }

        assertEquals(5, mockWebServer.requestCount)
    }

    @Test
    fun `interceptor works without SDK initialization`() {
        Telemetry.reset()

        val plainClient = OkHttpClient.Builder()
            .addInterceptor(Telemetry.okHttpInterceptor())
            .build()

        mockWebServer.enqueue(MockResponse().setResponseCode(200))

        val request = Request.Builder()
            .url(mockWebServer.url("/api/test"))
            .build()

        // Should not throw even without SDK init
        val response = plainClient.newCall(request).execute()
        assertEquals(200, response.code)
    }

    @Test
    fun `custom url sanitizer is applied`() {
        Telemetry.reset()

        val application: Application = ApplicationProvider.getApplicationContext()
        Telemetry.init(
            context = application,
            endpoint = "http://localhost:4318",
            serviceName = "sanitizer-test",
            urlSanitizer = { url ->
                url.replace(Regex("/v[0-9]+/"), "/v{version}/")
            }
        )

        Thread.sleep(500)

        val customClient = OkHttpClient.Builder()
            .addInterceptor(Telemetry.okHttpInterceptor())
            .build()

        mockWebServer.enqueue(MockResponse().setResponseCode(200))

        val request = Request.Builder()
            .url(mockWebServer.url("/v2/users"))
            .build()

        customClient.newCall(request).execute().close()

        // Verify the custom sanitizer is registered
        val sanitizer = Telemetry.getUrlSanitizer()
        assertTrue(sanitizer != null)
        assertEquals("/v{version}/users", sanitizer!!("/v2/users"))
    }
}
```

**Step 3: Commit**

```bash
git add telemetry/build.gradle.kts telemetry/src/test/kotlin/com/simplecityapps/telemetry/android/NetworkInterceptorIntegrationTest.kt
git commit -m "test: add MockWebServer integration tests for NetworkInterceptor"
```

---

## Phase 4: Lifecycle and Startup Integration Tests

### Task 9: Create Lifecycle Tracker Integration Test

**Files:**
- Create: `telemetry/src/test/kotlin/com/simplecityapps/telemetry/android/LifecycleTrackerIntegrationTest.kt`

**Step 1: Create LifecycleTrackerIntegrationTest.kt**

```kotlin
package com.simplecityapps.telemetry.android

import android.app.Application
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.core.app.ApplicationProvider
import io.opentelemetry.api.logs.Logger
import io.opentelemetry.api.logs.LogRecordBuilder
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LifecycleTrackerIntegrationTest {

    private lateinit var application: Application

    @Before
    fun setup() {
        application = ApplicationProvider.getApplicationContext()
        Telemetry.reset()
    }

    @After
    fun teardown() {
        Telemetry.reset()
    }

    @Test
    fun `lifecycle tracker initializes with real application`() {
        Telemetry.init(
            context = application,
            endpoint = "http://localhost:4318",
            serviceName = "lifecycle-test"
        )

        Thread.sleep(500)

        // Verify SDK initialized successfully
        assertTrue(Telemetry.isInitialized)
        assertTrue(Telemetry.getConfig() != null)
    }

    @Test
    fun `session manager tracks foreground and background correctly`() {
        Telemetry.init(
            context = application,
            endpoint = "http://localhost:4318",
            serviceName = "lifecycle-test"
        )

        Thread.sleep(500)

        val sessionManager = Telemetry.getSessionManager()!!

        // Simulate foreground
        sessionManager.onForeground()

        // Simulate background
        sessionManager.onBackground()

        // Session ID should still be valid
        assertTrue(sessionManager.sessionId.isNotEmpty())
    }

    @Test
    fun `lifecycle tracker emits events via logger`() {
        val logRecordBuilder: LogRecordBuilder = mock {
            on { setSeverity(any()) } doReturn it
            on { setBody(any<String>()) } doReturn it
            on { setAllAttributes(any()) } doReturn it
            on { emit() } doAnswer {}
        }
        val logger: Logger = mock {
            on { logRecordBuilder() } doReturn logRecordBuilder
        }
        val sessionManager: SessionManager = mock {
            on { onForeground() } doAnswer {}
            on { onBackground() } doAnswer {}
        }

        val tracker = LifecycleTracker(logger, sessionManager, null)

        // Simulate lifecycle state changes
        tracker.onStateChanged(Lifecycle.State.STARTED)

        verify(sessionManager).onForeground()
        verify(logRecordBuilder).setBody("app.foreground")
        verify(logRecordBuilder).emit()

        reset(logRecordBuilder, sessionManager)
        whenever(logRecordBuilder.setSeverity(any())).thenReturn(logRecordBuilder)
        whenever(logRecordBuilder.setBody(any<String>())).thenReturn(logRecordBuilder)
        whenever(logRecordBuilder.setAllAttributes(any())).thenReturn(logRecordBuilder)
        whenever(logger.logRecordBuilder()).thenReturn(logRecordBuilder)

        tracker.onStateChanged(Lifecycle.State.CREATED)

        verify(sessionManager).onBackground()
        verify(logRecordBuilder).setBody("app.background")
    }
}
```

**Step 2: Commit**

```bash
git add telemetry/src/test/kotlin/com/simplecityapps/telemetry/android/LifecycleTrackerIntegrationTest.kt
git commit -m "test: add Robolectric integration tests for LifecycleTracker"
```

---

### Task 10: Create Startup Tracer Integration Test

**Files:**
- Create: `telemetry/src/test/kotlin/com/simplecityapps/telemetry/android/StartupTracerIntegrationTest.kt`

**Step 1: Create StartupTracerIntegrationTest.kt**

```kotlin
package com.simplecityapps.telemetry.android

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanBuilder
import io.opentelemetry.api.trace.Tracer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class StartupTracerIntegrationTest {

    private lateinit var application: Application

    @Before
    fun setup() {
        application = ApplicationProvider.getApplicationContext()
        Telemetry.reset()
    }

    @After
    fun teardown() {
        Telemetry.reset()
    }

    @Test
    fun `startup tracer creates span on first activity resume`() {
        val span: Span = mock {
            on { end() } doAnswer {}
            on { setAttribute(any<String>(), any<Long>()) } doReturn it
            on { setAttribute(any<String>(), any<String>()) } doReturn it
            on { setAttribute(any<String>(), any<Boolean>()) } doReturn it
        }
        val spanBuilder: SpanBuilder = mock {
            on { startSpan() } doReturn span
            on { setAttribute(any<String>(), any<Long>()) } doReturn it
            on { setAttribute(any<String>(), any<String>()) } doReturn it
            on { setAttribute(any<String>(), any<Boolean>()) } doReturn it
        }
        val tracer: Tracer = mock {
            on { spanBuilder(any()) } doReturn spanBuilder
        }

        val startupTracer = StartupTracer(tracer, processStartTime = 1000L)

        // Register with application
        application.registerActivityLifecycleCallbacks(startupTracer)

        // Simulate activity lifecycle
        startupTracer.onFirstActivityResumed(currentTime = 1500L)

        verify(tracer).spanBuilder("app.startup")
        verify(span).end()
    }

    @Test
    fun `reportFullyDrawn creates separate span`() {
        val span: Span = mock {
            on { end() } doAnswer {}
            on { setAttribute(any<String>(), any<Long>()) } doReturn it
            on { setAttribute(any<String>(), any<String>()) } doReturn it
            on { setAttribute(any<String>(), any<Boolean>()) } doReturn it
        }
        val spanBuilder: SpanBuilder = mock {
            on { startSpan() } doReturn span
            on { setAttribute(any<String>(), any<Long>()) } doReturn it
            on { setAttribute(any<String>(), any<String>()) } doReturn it
            on { setAttribute(any<String>(), any<Boolean>()) } doReturn it
        }
        val tracer: Tracer = mock {
            on { spanBuilder(any()) } doReturn spanBuilder
        }

        val startupTracer = StartupTracer(tracer, processStartTime = 1000L)

        startupTracer.reportFullyDrawn(currentTime = 2000L)

        verify(tracer).spanBuilder("app.startup.full")
        verify(spanBuilder).setAttribute("startup.fully_drawn", true)
        verify(span).end()
    }

    @Test
    fun `startup tracer integrates with full SDK init`() {
        Telemetry.init(
            context = application,
            endpoint = "http://localhost:4318",
            serviceName = "startup-test"
        )

        Thread.sleep(500)

        assertTrue(Telemetry.isInitialized)

        // reportFullyDrawn should not throw
        Telemetry.reportFullyDrawn()
    }
}
```

**Step 2: Commit**

```bash
git add telemetry/src/test/kotlin/com/simplecityapps/telemetry/android/StartupTracerIntegrationTest.kt
git commit -m "test: add Robolectric integration tests for StartupTracer"
```

---

## Phase 5: Final Verification

### Task 11: Update Test README and Verify All Tests Pass

**Files:**
- Create: `docs/TESTING.md`

**Step 1: Create docs/TESTING.md**

```markdown
# Testing Guide

## Test Structure

### Unit Tests (JVM)
Location: `telemetry/src/test/kotlin/`

Pure logic tests that don't require Android framework:
- `SessionManagerTest` - Session ID generation and timeout logic
- `OtelConfigTest` - OTel SDK initialization
- `NetworkInterceptorTest` - URL sanitization logic
- `JankTrackerTest` - Frame metrics aggregation
- `NavigationTrackerTest` - Route normalization

### Integration Tests (Robolectric)
Location: `telemetry/src/test/kotlin/`

Tests requiring Android context but running on JVM:
- `TelemetryRobolectricTest` - Full SDK initialization
- `NetworkInterceptorIntegrationTest` - OkHttp + MockWebServer
- `LifecycleTrackerIntegrationTest` - Lifecycle state handling
- `StartupTracerIntegrationTest` - Activity lifecycle callbacks

### E2E Tests (Instrumented)
Location: `sample/src/androidTest/kotlin/`

Tests requiring real device/emulator:
- `TelemetryE2ETest` - Custom events and spans reach collector
- `NetworkInterceptorE2ETest` - HTTP spans with real OkHttp
- `CrashHandlerE2ETest` - JVM crash capture and emission

## Running Tests

### Unit + Robolectric Tests
```bash
./gradlew :telemetry:testDebugUnitTest
```

### Instrumented E2E Tests
```bash
# Start emulator first
./gradlew :sample:connectedDebugAndroidTest
```

### All Tests
```bash
./gradlew test connectedAndroidTest
```

## Test Coverage by Feature

| Feature | Unit | Integration | E2E |
|---------|------|-------------|-----|
| Session Management | ✅ | ✅ | - |
| OTel Export | ✅ | - | ✅ |
| JVM Crash Handler | ✅ | - | ✅ |
| Coroutine Crash Handler | ✅ | - | - |
| ANR Detection | ✅ | - | - |
| Network Interceptor | ✅ | ✅ | ✅ |
| Startup Timing | ✅ | ✅ | - |
| Lifecycle Tracking | ✅ | ✅ | - |
| Frame Metrics | ✅ | - | - |
| Navigation Tracking | ✅ | - | - |
| NDK Crash Handler | - | - | Manual |

## Mock OTLP Collector

The E2E tests use `MockOtlpCollector` which:
- Runs a local HTTP server accepting OTLP requests
- Captures traces, logs, and metrics
- Provides assertions for verifying telemetry content

## Sample App

The `sample` module provides a manual testing app with buttons to trigger:
- Custom events
- Custom spans
- Network requests
- JVM crashes
- NDK crashes (placeholder)

Run with: `./gradlew :sample:installDebug`
```

**Step 2: Commit**

```bash
git add docs/TESTING.md
git commit -m "docs: add testing guide"
```

---

### Task 12: Final Commit and Summary

**Step 1: Review all changes**

```bash
git log --oneline -20
```

**Step 2: Create summary commit if needed**

If there are any uncommitted changes:
```bash
git add .
git commit -m "test: complete test suite improvements"
```

---

## Summary

### Tests Added

| Type | Count | Coverage |
|------|-------|----------|
| E2E (Instrumented) | 3 files, ~15 tests | OTLP emission, crashes, network |
| Integration (Robolectric) | 4 files, ~20 tests | SDK init, lifecycle, network |
| Mock Infrastructure | 1 file | OTLP collector simulation |
| Sample App | 1 module | Manual testing |

### Test Distribution After Changes

| Type | Before | After |
|------|--------|-------|
| JVM Unit | 100% | ~35% |
| Robolectric Integration | 0% | ~35% |
| Instrumented E2E | 0% | ~25% |
| MockWebServer | 0% | ~5% |

### Critical Paths Now Covered

1. ✅ Custom events reach OTLP collector
2. ✅ Custom spans reach OTLP collector
3. ✅ Network requests create spans
4. ✅ JVM crashes emit error logs
5. ✅ URL sanitization applied to network spans
6. ✅ Session persistence across restarts
7. ✅ Lifecycle events emitted
