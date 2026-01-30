# Android Telemetry SDK Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Build a lightweight Android observability SDK that captures crashes, ANRs, performance metrics, and navigation events, exporting via OTLP/HTTP.

**Architecture:** Singleton `Telemetry` facade delegates to specialized collectors (CrashHandler, AnrWatchdog, etc.). All collectors emit to a shared OpenTelemetry SDK instance configured with BatchSpanProcessor. SessionManager provides resource attributes (session ID, device info) attached to all telemetry.

**Tech Stack:** Kotlin, OpenTelemetry SDK, AndroidX Lifecycle, JankStats, OkHttp, JNI/C for NDK crashes

**Design Doc:** `docs/plans/2026-01-30-android-telemetry-sdk-design.md`

---

## Phase 1: Project Foundation

### Task 1: Create Android Library Module Structure

**Files:**
- Create: `telemetry/build.gradle.kts`
- Create: `telemetry/src/main/AndroidManifest.xml`
- Create: `telemetry/consumer-rules.pro`
- Create: `telemetry/proguard-rules.pro`
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts` (root)
- Create: `gradle.properties`

**Step 1: Create root build.gradle.kts**

```kotlin
// build.gradle.kts (root)
plugins {
    id("com.android.library") version "8.2.0" apply false
    id("org.jetbrains.kotlin.android") version "1.9.21" apply false
}
```

**Step 2: Create settings.gradle.kts**

```kotlin
// settings.gradle.kts
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "android-telemetry"
include(":telemetry")
```

**Step 3: Create gradle.properties**

```properties
# gradle.properties
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
android.useAndroidX=true
kotlin.code.style=official
android.nonTransitiveRClass=true
```

**Step 4: Create telemetry/build.gradle.kts**

```kotlin
// telemetry/build.gradle.kts
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.simplecityapps.telemetry.android"
    compileSdk = 34

    defaultConfig {
        minSdk = 26

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")

        externalNativeBuild {
            cmake {
                cppFlags("")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    // OpenTelemetry
    implementation("io.opentelemetry:opentelemetry-api:1.32.0")
    implementation("io.opentelemetry:opentelemetry-sdk:1.32.0")
    implementation("io.opentelemetry:opentelemetry-exporter-otlp:1.32.0")
    implementation("io.opentelemetry:opentelemetry-semconv:1.23.1-alpha")

    // AndroidX
    implementation("androidx.lifecycle:lifecycle-process:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.metrics:metrics-performance:1.0.0-beta01")
    implementation("androidx.core:core-ktx:1.12.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // OkHttp (compileOnly - consumer provides)
    compileOnly("com.squareup.okhttp3:okhttp:4.12.0")

    // Navigation (compileOnly - consumer provides)
    compileOnly("androidx.navigation:navigation-compose:2.7.6")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.2.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("io.opentelemetry:opentelemetry-sdk-testing:1.32.0")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:runner:1.5.2")
}
```

**Step 5: Create telemetry/src/main/AndroidManifest.xml**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />

</manifest>
```

**Step 6: Create consumer-rules.pro**

```proguard
# consumer-rules.pro

# OTel SDK uses reflection for Resource attributes
-keepattributes *Annotation*
-keep class io.opentelemetry.** { *; }
-keep class io.opentelemetry.sdk.resources.** { *; }
-keep class io.opentelemetry.semconv.** { *; }

# Keep telemetry SDK public API
-keep class com.simplecityapps.telemetry.android.Telemetry { *; }
-keep class com.simplecityapps.telemetry.android.Telemetry$* { *; }

# JNI bridge for NDK crash handler
-keep class com.simplecityapps.telemetry.android.NdkCrashHandler { *; }

# Coroutine exception handler loaded via ServiceLoader
-keep class com.simplecityapps.telemetry.android.TelemetryCoroutineExceptionHandler { *; }
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler

# ServiceLoader
-keepnames class kotlin.coroutines.Continuation
-keep class META-INF.services.** { *; }
```

**Step 7: Create empty proguard-rules.pro**

```proguard
# proguard-rules.pro
# Library-internal rules (none needed currently)
```

**Step 8: Create placeholder CMakeLists.txt**

```cmake
# telemetry/src/main/cpp/CMakeLists.txt
cmake_minimum_required(VERSION 3.22.1)

project("telemetry")

add_library(${CMAKE_PROJECT_NAME} SHARED
    ndk_crash_handler.c
)

target_link_libraries(${CMAKE_PROJECT_NAME}
    android
    log
)
```

**Step 9: Create placeholder ndk_crash_handler.c**

```c
// telemetry/src/main/cpp/ndk_crash_handler.c
#include <jni.h>

// Placeholder - full implementation in Task 14
JNIEXPORT void JNICALL
Java_com_simplecityapps_telemetry_android_NdkCrashHandler_nativeInit(
    JNIEnv *env,
    jobject thiz,
    jstring crash_file_path
) {
    // TODO: Implement in Task 14
}
```

**Step 10: Verify project builds**

Run: `./gradlew :telemetry:assembleDebug`
Expected: BUILD SUCCESSFUL

**Step 11: Commit**

```bash
git add .
git commit -m "chore: scaffold Android library module with dependencies"
```

---

## Phase 2: Core Infrastructure

### Task 2: Implement SessionManager

**Files:**
- Create: `telemetry/src/main/kotlin/com/simplecityapps/telemetry/android/SessionManager.kt`
- Create: `telemetry/src/test/kotlin/com/simplecityapps/telemetry/android/SessionManagerTest.kt`

**Step 1: Write failing test for session ID generation**

```kotlin
// telemetry/src/test/kotlin/com/simplecityapps/telemetry/android/SessionManagerTest.kt
package com.simplecityapps.telemetry.android

import android.content.Context
import android.content.SharedPreferences
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*
import java.util.UUID
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SessionManagerTest {

    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor

    @Before
    fun setup() {
        editor = mock {
            on { putLong(any(), any()) } doReturn it
            on { putString(any(), any()) } doReturn it
            on { apply() } doAnswer {}
        }
        prefs = mock {
            on { edit() } doReturn editor
            on { getLong(eq("last_background_time"), any()) } doReturn 0L
            on { getString(eq("session_id"), any()) } doReturn null
        }
        context = mock {
            on { getSharedPreferences(any(), any()) } doReturn prefs
            on { packageName } doReturn "com.test.app"
        }
    }

    @Test
    fun `generates valid UUID session ID on cold start`() {
        val manager = SessionManager(context)

        val sessionId = manager.sessionId

        assertNotNull(sessionId)
        // Verify it's a valid UUID format
        UUID.fromString(sessionId)
    }

    @Test
    fun `regenerates session ID after 30 minute timeout`() {
        val thirtyOneMinutesAgo = System.currentTimeMillis() - (31 * 60 * 1000)
        whenever(prefs.getLong(eq("last_background_time"), any())).thenReturn(thirtyOneMinutesAgo)
        whenever(prefs.getString(eq("session_id"), any())).thenReturn("old-session-id")

        val manager = SessionManager(context)
        manager.onForeground()

        verify(editor).putString(eq("session_id"), argThat { it != "old-session-id" })
    }

    @Test
    fun `keeps session ID if under 30 minute timeout`() {
        val tenMinutesAgo = System.currentTimeMillis() - (10 * 60 * 1000)
        val existingSessionId = UUID.randomUUID().toString()
        whenever(prefs.getLong(eq("last_background_time"), any())).thenReturn(tenMinutesAgo)
        whenever(prefs.getString(eq("session_id"), any())).thenReturn(existingSessionId)

        val manager = SessionManager(context)
        manager.onForeground()

        assertTrue(manager.sessionId == existingSessionId)
    }

    @Test
    fun `records background timestamp on background`() {
        val manager = SessionManager(context)

        manager.onBackground()

        verify(editor).putLong(eq("last_background_time"), any())
        verify(editor).apply()
    }
}
```

**Step 2: Run test to verify it fails**

Run: `./gradlew :telemetry:testDebugUnitTest --tests "*.SessionManagerTest"`
Expected: FAIL - class not found

**Step 3: Implement SessionManager**

```kotlin
// telemetry/src/main/kotlin/com/simplecityapps/telemetry/android/SessionManager.kt
package com.simplecityapps.telemetry.android

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

internal class SessionManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    private val appContext = context.applicationContext
    private val _sessionId = AtomicReference<String>()
    private val _userId = AtomicReference<String?>(null)
    private val _customAttributes = mutableMapOf<String, String>()
    private val attributesLock = Any()

    val sessionId: String
        get() = _sessionId.get()

    val userId: String?
        get() = _userId.get()

    val customAttributes: Map<String, String>
        get() = synchronized(attributesLock) { _customAttributes.toMap() }

    init {
        initializeSession()
    }

    private fun initializeSession() {
        val existingId = prefs.getString(KEY_SESSION_ID, null)
        val lastBackground = prefs.getLong(KEY_LAST_BACKGROUND, 0L)
        val now = System.currentTimeMillis()

        val shouldRegenerate = existingId == null ||
            (lastBackground > 0 && (now - lastBackground) > SESSION_TIMEOUT_MS)

        val newSessionId = if (shouldRegenerate) {
            UUID.randomUUID().toString().also { id ->
                prefs.edit().putString(KEY_SESSION_ID, id).apply()
            }
        } else {
            existingId
        }

        _sessionId.set(newSessionId)
    }

    fun onForeground() {
        val lastBackground = prefs.getLong(KEY_LAST_BACKGROUND, 0L)
        val now = System.currentTimeMillis()

        if (lastBackground > 0 && (now - lastBackground) > SESSION_TIMEOUT_MS) {
            val newId = UUID.randomUUID().toString()
            _sessionId.set(newId)
            prefs.edit().putString(KEY_SESSION_ID, newId).apply()
        }
    }

    fun onBackground() {
        prefs.edit()
            .putLong(KEY_LAST_BACKGROUND, System.currentTimeMillis())
            .apply()
    }

    fun setUserId(id: String?) {
        _userId.set(id)
    }

    fun setAttribute(key: String, value: String) {
        synchronized(attributesLock) {
            _customAttributes[key] = value
        }
    }

    fun getDeviceModel(): String = Build.MODEL

    fun getDeviceManufacturer(): String = Build.MANUFACTURER

    fun getOsVersion(): String = Build.VERSION.SDK_INT.toString()

    fun getAppVersion(context: Context): String {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }
    }

    fun getAppVersionCode(context: Context): Long {
        return try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
        } catch (e: Exception) {
            0L
        }
    }

    companion object {
        private const val PREFS_NAME = "telemetry_session"
        private const val KEY_SESSION_ID = "session_id"
        private const val KEY_LAST_BACKGROUND = "last_background_time"
        private const val SESSION_TIMEOUT_MS = 30 * 60 * 1000L // 30 minutes
    }
}
```

**Step 4: Run tests to verify they pass**

Run: `./gradlew :telemetry:testDebugUnitTest --tests "*.SessionManagerTest"`
Expected: PASS

**Step 5: Commit**

```bash
git add telemetry/src/main/kotlin/com/simplecityapps/telemetry/android/SessionManager.kt \
        telemetry/src/test/kotlin/com/simplecityapps/telemetry/android/SessionManagerTest.kt
git commit -m "feat: add SessionManager with 30-min timeout logic"
```

---

### Task 3: Implement OtelConfig

**Files:**
- Create: `telemetry/src/main/kotlin/com/simplecityapps/telemetry/android/OtelConfig.kt`
- Create: `telemetry/src/test/kotlin/com/simplecityapps/telemetry/android/OtelConfigTest.kt`

**Step 1: Write failing test for OTel SDK initialization**

```kotlin
// telemetry/src/test/kotlin/com/simplecityapps/telemetry/android/OtelConfigTest.kt
package com.simplecityapps.telemetry.android

import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.api.logs.Logger
import io.opentelemetry.api.metrics.Meter
import org.junit.Test
import kotlin.test.assertNotNull

class OtelConfigTest {

    @Test
    fun `creates tracer with service name`() {
        val config = OtelConfig(
            endpoint = "http://localhost:4318",
            serviceName = "test-service",
            sessionId = "test-session",
            deviceModel = "Pixel",
            deviceManufacturer = "Google",
            osVersion = "34",
            appVersion = "1.0.0",
            appVersionCode = 1L
        )

        val tracer: Tracer = config.tracer

        assertNotNull(tracer)
    }

    @Test
    fun `creates logger for crash reporting`() {
        val config = OtelConfig(
            endpoint = "http://localhost:4318",
            serviceName = "test-service",
            sessionId = "test-session",
            deviceModel = "Pixel",
            deviceManufacturer = "Google",
            osVersion = "34",
            appVersion = "1.0.0",
            appVersionCode = 1L
        )

        val logger: Logger = config.logger

        assertNotNull(logger)
    }

    @Test
    fun `creates meter for frame metrics`() {
        val config = OtelConfig(
            endpoint = "http://localhost:4318",
            serviceName = "test-service",
            sessionId = "test-session",
            deviceModel = "Pixel",
            deviceManufacturer = "Google",
            osVersion = "34",
            appVersion = "1.0.0",
            appVersionCode = 1L
        )

        val meter: Meter = config.meter

        assertNotNull(meter)
    }

    @Test
    fun `shutdown completes without error`() {
        val config = OtelConfig(
            endpoint = "http://localhost:4318",
            serviceName = "test-service",
            sessionId = "test-session",
            deviceModel = "Pixel",
            deviceManufacturer = "Google",
            osVersion = "34",
            appVersion = "1.0.0",
            appVersionCode = 1L
        )

        config.shutdown()
        // No exception = pass
    }
}
```

**Step 2: Run test to verify it fails**

Run: `./gradlew :telemetry:testDebugUnitTest --tests "*.OtelConfigTest"`
Expected: FAIL - class not found

**Step 3: Implement OtelConfig**

```kotlin
// telemetry/src/main/kotlin/com/simplecityapps/telemetry/android/OtelConfig.kt
package com.simplecityapps.telemetry.android

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.logs.Logger
import io.opentelemetry.api.metrics.Meter
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.exporter.otlp.http.logs.OtlpHttpLogRecordExporter
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporter
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.logs.SdkLoggerProvider
import io.opentelemetry.sdk.logs.export.BatchLogRecordProcessor
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor
import java.time.Duration
import java.util.concurrent.TimeUnit

internal class OtelConfig(
    endpoint: String,
    serviceName: String,
    sessionId: String,
    deviceModel: String,
    deviceManufacturer: String,
    osVersion: String,
    appVersion: String,
    appVersionCode: Long
) {
    private val resource: Resource = Resource.create(
        Attributes.builder()
            .put(AttributeKey.stringKey("service.name"), serviceName)
            .put(AttributeKey.stringKey("session.id"), sessionId)
            .put(AttributeKey.stringKey("device.model"), deviceModel)
            .put(AttributeKey.stringKey("device.manufacturer"), deviceManufacturer)
            .put(AttributeKey.stringKey("os.version"), osVersion)
            .put(AttributeKey.stringKey("app.version"), appVersion)
            .put(AttributeKey.longKey("app.version.code"), appVersionCode)
            .build()
    )

    private val spanExporter = OtlpHttpSpanExporter.builder()
        .setEndpoint("$endpoint/v1/traces")
        .setTimeout(Duration.ofSeconds(10))
        .build()

    private val logExporter = OtlpHttpLogRecordExporter.builder()
        .setEndpoint("$endpoint/v1/logs")
        .setTimeout(Duration.ofSeconds(10))
        .build()

    private val metricExporter = OtlpHttpMetricExporter.builder()
        .setEndpoint("$endpoint/v1/metrics")
        .setTimeout(Duration.ofSeconds(10))
        .build()

    private val tracerProvider = SdkTracerProvider.builder()
        .setResource(resource)
        .addSpanProcessor(
            BatchSpanProcessor.builder(spanExporter)
                .setMaxQueueSize(MAX_QUEUE_SIZE)
                .setMaxExportBatchSize(MAX_BATCH_SIZE)
                .setScheduleDelay(Duration.ofSeconds(EXPORT_INTERVAL_SECONDS))
                .build()
        )
        .build()

    private val loggerProvider = SdkLoggerProvider.builder()
        .setResource(resource)
        .addLogRecordProcessor(
            BatchLogRecordProcessor.builder(logExporter)
                .setMaxQueueSize(MAX_QUEUE_SIZE)
                .setMaxExportBatchSize(MAX_BATCH_SIZE)
                .setScheduleDelay(Duration.ofSeconds(EXPORT_INTERVAL_SECONDS))
                .build()
        )
        .build()

    private val meterProvider = SdkMeterProvider.builder()
        .setResource(resource)
        .registerMetricReader(
            PeriodicMetricReader.builder(metricExporter)
                .setInterval(Duration.ofSeconds(EXPORT_INTERVAL_SECONDS))
                .build()
        )
        .build()

    private val sdk = OpenTelemetrySdk.builder()
        .setTracerProvider(tracerProvider)
        .setLoggerProvider(loggerProvider)
        .setMeterProvider(meterProvider)
        .build()

    val tracer: Tracer = sdk.getTracer(INSTRUMENTATION_NAME, INSTRUMENTATION_VERSION)

    val logger: Logger = sdk.logsBridge.loggerBuilder(INSTRUMENTATION_NAME).build()

    val meter: Meter = sdk.getMeter(INSTRUMENTATION_NAME)

    fun shutdown() {
        try {
            sdk.shutdown().join(5, TimeUnit.SECONDS)
        } catch (e: Exception) {
            // Silently ignore shutdown errors
        }
    }

    fun forceFlush() {
        try {
            tracerProvider.forceFlush().join(2, TimeUnit.SECONDS)
            loggerProvider.forceFlush().join(2, TimeUnit.SECONDS)
        } catch (e: Exception) {
            // Silently ignore flush errors
        }
    }

    companion object {
        private const val INSTRUMENTATION_NAME = "com.simplecityapps.telemetry.android"
        private const val INSTRUMENTATION_VERSION = "1.0.0"
        private const val MAX_QUEUE_SIZE = 200
        private const val MAX_BATCH_SIZE = 50
        private const val EXPORT_INTERVAL_SECONDS = 30L
    }
}
```

**Step 4: Run tests to verify they pass**

Run: `./gradlew :telemetry:testDebugUnitTest --tests "*.OtelConfigTest"`
Expected: PASS

**Step 5: Commit**

```bash
git add telemetry/src/main/kotlin/com/simplecityapps/telemetry/android/OtelConfig.kt \
        telemetry/src/test/kotlin/com/simplecityapps/telemetry/android/OtelConfigTest.kt
git commit -m "feat: add OtelConfig with OTLP/HTTP exporters and batch processing"
```

---

### Task 4: Implement Telemetry Facade (Public API)

**Files:**
- Create: `telemetry/src/main/kotlin/com/simplecityapps/telemetry/android/Telemetry.kt`
- Create: `telemetry/src/test/kotlin/com/simplecityapps/telemetry/android/TelemetryTest.kt`

**Step 1: Write failing test for initialization**

```kotlin
// telemetry/src/test/kotlin/com/simplecityapps/telemetry/android/TelemetryTest.kt
package com.simplecityapps.telemetry.android

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TelemetryTest {

    private lateinit var application: Application
    private lateinit var prefs: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor
    private lateinit var packageManager: PackageManager

    @Before
    fun setup() {
        editor = mock {
            on { putLong(any(), any()) } doReturn it
            on { putString(any(), any()) } doReturn it
            on { apply() } doAnswer {}
        }
        prefs = mock {
            on { edit() } doReturn editor
            on { getLong(any(), any()) } doReturn 0L
            on { getString(any(), any()) } doReturn null
        }
        packageManager = mock {
            on { getPackageInfo(any<String>(), any<Int>()) } doReturn PackageInfo().apply {
                versionName = "1.0.0"
                @Suppress("DEPRECATION")
                versionCode = 1
            }
        }
        application = mock {
            on { applicationContext } doReturn it
            on { getSharedPreferences(any(), any()) } doReturn prefs
            on { packageName } doReturn "com.test.app"
            on { packageManager } doReturn packageManager
        }

        // Reset singleton state
        Telemetry.reset()
    }

    @After
    fun teardown() {
        Telemetry.reset()
    }

    @Test
    fun `init returns immediately without blocking`() {
        val startTime = System.currentTimeMillis()

        Telemetry.init(
            context = application,
            endpoint = "http://localhost:4318",
            serviceName = "test-app"
        )

        val duration = System.currentTimeMillis() - startTime
        assertTrue(duration < 100, "init should return quickly, took ${duration}ms")
    }

    @Test
    fun `isInitialized returns true after init`() {
        assertFalse(Telemetry.isInitialized)

        Telemetry.init(
            context = application,
            endpoint = "http://localhost:4318",
            serviceName = "test-app"
        )

        assertTrue(Telemetry.isInitialized)
    }

    @Test
    fun `startSpan returns non-null span after init`() {
        Telemetry.init(
            context = application,
            endpoint = "http://localhost:4318",
            serviceName = "test-app"
        )

        val span = Telemetry.startSpan("test-span")

        assertNotNull(span)
        span.end()
    }

    @Test
    fun `logEvent does not throw before init`() {
        // Should not throw, just no-op
        Telemetry.logEvent("test-event", mapOf("key" to "value"))
    }

    @Test
    fun `setUserId does not throw`() {
        Telemetry.init(
            context = application,
            endpoint = "http://localhost:4318",
            serviceName = "test-app"
        )

        Telemetry.setUserId("user-123")
        // No exception = pass
    }

    @Test
    fun `setAttribute does not throw`() {
        Telemetry.init(
            context = application,
            endpoint = "http://localhost:4318",
            serviceName = "test-app"
        )

        Telemetry.setAttribute("tier", "premium")
        // No exception = pass
    }
}
```

**Step 2: Run test to verify it fails**

Run: `./gradlew :telemetry:testDebugUnitTest --tests "*.TelemetryTest"`
Expected: FAIL - class not found

**Step 3: Implement Telemetry facade**

```kotlin
// telemetry/src/main/kotlin/com/simplecityapps/telemetry/android/Telemetry.kt
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import okhttp3.Interceptor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

object Telemetry {

    private val initialized = AtomicBoolean(false)
    private val configRef = AtomicReference<OtelConfig?>(null)
    private val sessionManagerRef = AtomicReference<SessionManager?>(null)

    private val telemetryExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "telemetry-worker").apply { isDaemon = true }
    }
    private val telemetryDispatcher = telemetryExecutor.asCoroutineDispatcher()
    private val telemetryScope = CoroutineScope(SupervisorJob() + telemetryDispatcher)

    private var urlSanitizer: ((String) -> String)? = null

    val isInitialized: Boolean
        get() = initialized.get()

    /**
     * Initialize the telemetry SDK. Returns immediately; setup continues async.
     */
    fun init(
        context: Context,
        endpoint: String,
        serviceName: String,
        urlSanitizer: ((String) -> String)? = null
    ) {
        if (!initialized.compareAndSet(false, true)) {
            return // Already initialized
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

                // Initialize collectors
                initializeCollectors(appContext as Application)

            } catch (e: Exception) {
                // Log internally but don't crash the host app
                android.util.Log.e(TAG, "Failed to initialize telemetry", e)
            }
        }
    }

    private fun initializeCollectors(application: Application) {
        // Collectors will be added in subsequent tasks
    }

    /**
     * Start a custom span for instrumenting app operations.
     */
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

    /**
     * Log a custom event with optional attributes.
     */
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

    /**
     * Report that the app is fully drawn and interactive.
     */
    fun reportFullyDrawn() {
        // Will be implemented by StartupTracer
    }

    /**
     * Track Compose navigation events.
     */
    suspend fun trackNavigation(navController: NavController) {
        // Will be implemented by NavigationTracker
    }

    /**
     * Get OkHttp interceptor for network monitoring.
     */
    fun okHttpInterceptor(): Interceptor {
        return NetworkInterceptor(this::urlSanitizer)
    }

    /**
     * Set the current user ID for attribution.
     */
    fun setUserId(id: String?) {
        sessionManagerRef.get()?.setUserId(id)
    }

    /**
     * Set a custom attribute that will be attached to all telemetry.
     */
    fun setAttribute(key: String, value: String) {
        sessionManagerRef.get()?.setAttribute(key, value)
    }

    internal fun getConfig(): OtelConfig? = configRef.get()

    internal fun getSessionManager(): SessionManager? = sessionManagerRef.get()

    internal fun getTelemetryScope(): CoroutineScope = telemetryScope

    internal fun getUrlSanitizer(): ((String) -> String)? = urlSanitizer

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

    /**
     * For testing only - reset singleton state.
     */
    internal fun reset() {
        configRef.get()?.shutdown()
        configRef.set(null)
        sessionManagerRef.set(null)
        initialized.set(false)
        urlSanitizer = null
    }

    private const val TAG = "Telemetry"

    private object NoOpSpan : Span {
        override fun <T : Any?> setAttribute(key: AttributeKey<T>, value: T): Span = this
        override fun addEvent(name: String, attributes: Attributes): Span = this
        override fun addEvent(name: String, attributes: Attributes, timestamp: Long, unit: java.util.concurrent.TimeUnit): Span = this
        override fun addEvent(name: String): Span = this
        override fun addEvent(name: String, timestamp: Long, unit: java.util.concurrent.TimeUnit): Span = this
        override fun setStatus(statusCode: StatusCode, description: String): Span = this
        override fun setStatus(statusCode: StatusCode): Span = this
        override fun recordException(exception: Throwable, additionalAttributes: Attributes): Span = this
        override fun recordException(exception: Throwable): Span = this
        override fun updateName(name: String): Span = this
        override fun end() {}
        override fun end(timestamp: Long, unit: java.util.concurrent.TimeUnit) {}
        override fun getSpanContext() = io.opentelemetry.api.trace.SpanContext.getInvalid()
        override fun isRecording() = false
    }
}
```

**Step 4: Create placeholder NetworkInterceptor to satisfy compilation**

```kotlin
// telemetry/src/main/kotlin/com/simplecityapps/telemetry/android/NetworkInterceptor.kt
package com.simplecityapps.telemetry.android

import okhttp3.Interceptor
import okhttp3.Response

internal class NetworkInterceptor(
    private val urlSanitizerProvider: () -> ((String) -> String)?
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        // Full implementation in Task 8
        return chain.proceed(chain.request())
    }
}
```

**Step 5: Run tests to verify they pass**

Run: `./gradlew :telemetry:testDebugUnitTest --tests "*.TelemetryTest"`
Expected: PASS

**Step 6: Commit**

```bash
git add telemetry/src/main/kotlin/com/simplecityapps/telemetry/android/Telemetry.kt \
        telemetry/src/main/kotlin/com/simplecityapps/telemetry/android/NetworkInterceptor.kt \
        telemetry/src/test/kotlin/com/simplecityapps/telemetry/android/TelemetryTest.kt
git commit -m "feat: add Telemetry facade with public API"
```

---

## Phase 3: Crash Handling

### Task 5: Implement JVM Crash Handler

**Files:**
- Create: `telemetry/src/main/kotlin/com/simplecityapps/telemetry/android/CrashHandler.kt`
- Create: `telemetry/src/test/kotlin/com/simplecityapps/telemetry/android/CrashHandlerTest.kt`

**Step 1: Write failing test**

```kotlin
// telemetry/src/test/kotlin/com/simplecityapps/telemetry/android/CrashHandlerTest.kt
package com.simplecityapps.telemetry.android

import io.opentelemetry.api.logs.Logger
import io.opentelemetry.api.logs.LogRecordBuilder
import io.opentelemetry.api.logs.Severity
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*
import kotlin.test.assertTrue

class CrashHandlerTest {

    private lateinit var logger: Logger
    private lateinit var logRecordBuilder: LogRecordBuilder

    @Before
    fun setup() {
        logRecordBuilder = mock {
            on { setSeverity(any()) } doReturn it
            on { setBody(any<String>()) } doReturn it
            on { setAllAttributes(any()) } doReturn it
            on { emit() } doAnswer {}
        }
        logger = mock {
            on { logRecordBuilder() } doReturn logRecordBuilder
        }
    }

    @Test
    fun `captures exception and emits log with ERROR severity`() {
        var chainedHandlerCalled = false
        val previousHandler = Thread.UncaughtExceptionHandler { _, _ ->
            chainedHandlerCalled = true
        }

        val handler = CrashHandler(logger, previousHandler)
        val exception = RuntimeException("Test crash")
        val thread = Thread.currentThread()

        handler.uncaughtException(thread, exception)

        verify(logRecordBuilder).setSeverity(Severity.ERROR)
        verify(logRecordBuilder).setBody(contains("RuntimeException"))
        verify(logRecordBuilder).emit()
        assertTrue(chainedHandlerCalled, "Should chain to previous handler")
    }

    @Test
    fun `includes thread name in log`() {
        val handler = CrashHandler(logger, null)
        val exception = RuntimeException("Test crash")
        val thread = Thread("test-thread-name")

        handler.uncaughtException(thread, exception)

        verify(logRecordBuilder).setAllAttributes(argThat { attrs ->
            attrs.asMap().any { (key, value) ->
                key.key == "thread.name" && value == "test-thread-name"
            }
        })
    }

    @Test
    fun `handles null previous handler gracefully`() {
        val handler = CrashHandler(logger, null)
        val exception = RuntimeException("Test crash")

        // Should not throw
        handler.uncaughtException(Thread.currentThread(), exception)

        verify(logRecordBuilder).emit()
    }
}
```

**Step 2: Run test to verify it fails**

Run: `./gradlew :telemetry:testDebugUnitTest --tests "*.CrashHandlerTest"`
Expected: FAIL - class not found

**Step 3: Implement CrashHandler**

```kotlin
// telemetry/src/main/kotlin/com/simplecityapps/telemetry/android/CrashHandler.kt
package com.simplecityapps.telemetry.android

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.logs.Logger
import io.opentelemetry.api.logs.Severity
import java.io.PrintWriter
import java.io.StringWriter

internal class CrashHandler(
    private val logger: Logger,
    private val previousHandler: Thread.UncaughtExceptionHandler?
) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            emitCrashLog(thread, throwable)
        } catch (e: Exception) {
            // Never crash while handling a crash
        } finally {
            // Chain to previous handler
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun emitCrashLog(thread: Thread, throwable: Throwable) {
        val stackTrace = StringWriter().also { sw ->
            throwable.printStackTrace(PrintWriter(sw))
        }.toString()

        val attributes = Attributes.builder()
            .put(AttributeKey.stringKey("exception.type"), throwable.javaClass.name)
            .put(AttributeKey.stringKey("exception.message"), throwable.message ?: "")
            .put(AttributeKey.stringKey("exception.stacktrace"), stackTrace)
            .put(AttributeKey.stringKey("thread.name"), thread.name)
            .put(AttributeKey.longKey("thread.id"), thread.id)
            .put(AttributeKey.stringKey("crash.type"), "jvm")
            .build()

        logger.logRecordBuilder()
            .setSeverity(Severity.ERROR)
            .setBody("JVM Crash: ${throwable.javaClass.simpleName}: ${throwable.message}")
            .setAllAttributes(attributes)
            .emit()
    }

    companion object {
        fun install(logger: Logger) {
            val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
            val handler = CrashHandler(logger, previousHandler)
            Thread.setDefaultUncaughtExceptionHandler(handler)
        }
    }
}
```

**Step 4: Run tests to verify they pass**

Run: `./gradlew :telemetry:testDebugUnitTest --tests "*.CrashHandlerTest"`
Expected: PASS

**Step 5: Commit**

```bash
git add telemetry/src/main/kotlin/com/simplecityapps/telemetry/android/CrashHandler.kt \
        telemetry/src/test/kotlin/com/simplecityapps/telemetry/android/CrashHandlerTest.kt
git commit -m "feat: add JVM crash handler with exception chaining"
```

---

### Task 6: Implement Coroutine Exception Handler

**Files:**
- Modify: `telemetry/src/main/kotlin/com/simplecityapps/telemetry/android/CrashHandler.kt`
- Create: `telemetry/src/main/resources/META-INF/services/kotlinx.coroutines.CoroutineExceptionHandler`
- Create: `telemetry/src/test/kotlin/com/simplecityapps/telemetry/android/CoroutineExceptionHandlerTest.kt`

**Step 1: Write failing test**

```kotlin
// telemetry/src/test/kotlin/com/simplecityapps/telemetry/android/CoroutineExceptionHandlerTest.kt
package com.simplecityapps.telemetry.android

import io.opentelemetry.api.logs.Logger
import io.opentelemetry.api.logs.LogRecordBuilder
import io.opentelemetry.api.logs.Severity
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*
import kotlin.coroutines.CoroutineContext

class CoroutineExceptionHandlerTest {

    private lateinit var logger: Logger
    private lateinit var logRecordBuilder: LogRecordBuilder

    @Before
    fun setup() {
        logRecordBuilder = mock {
            on { setSeverity(any()) } doReturn it
            on { setBody(any<String>()) } doReturn it
            on { setAllAttributes(any()) } doReturn it
            on { emit() } doAnswer {}
        }
        logger = mock {
            on { logRecordBuilder() } doReturn logRecordBuilder
        }
    }

    @Test
    fun `captures coroutine exception with ERROR severity`() {
        val handler = TelemetryCoroutineExceptionHandler()
        handler.setLogger(logger)

        val exception = IllegalStateException("Coroutine failed")
        val context: CoroutineContext = Job() + CoroutineName("test-coroutine")

        handler.handleException(context, exception)

        verify(logRecordBuilder).setSeverity(Severity.ERROR)
        verify(logRecordBuilder).emit()
    }

    @Test
    fun `includes coroutine name in attributes`() {
        val handler = TelemetryCoroutineExceptionHandler()
        handler.setLogger(logger)

        val exception = RuntimeException("Test")
        val context: CoroutineContext = Job() + CoroutineName("my-coroutine")

        handler.handleException(context, exception)

        verify(logRecordBuilder).setAllAttributes(argThat { attrs ->
            attrs.asMap().any { (key, value) ->
                key.key == "coroutine.name" && value == "my-coroutine"
            }
        })
    }

    @Test
    fun `handles missing coroutine name gracefully`() {
        val handler = TelemetryCoroutineExceptionHandler()
        handler.setLogger(logger)

        val exception = RuntimeException("Test")
        val context: CoroutineContext = Job()

        // Should not throw
        handler.handleException(context, exception)

        verify(logRecordBuilder).emit()
    }
}
```

**Step 2: Run test to verify it fails**

Run: `./gradlew :telemetry:testDebugUnitTest --tests "*.CoroutineExceptionHandlerTest"`
Expected: FAIL - class not found

**Step 3: Add TelemetryCoroutineExceptionHandler to CrashHandler.kt**

```kotlin
// Append to telemetry/src/main/kotlin/com/simplecityapps/telemetry/android/CrashHandler.kt

class TelemetryCoroutineExceptionHandler : kotlinx.coroutines.CoroutineExceptionHandler {

    @Volatile
    private var logger: Logger? = null

    override val key: CoroutineContext.Key<*> = kotlinx.coroutines.CoroutineExceptionHandler

    fun setLogger(logger: Logger) {
        this.logger = logger
    }

    override fun handleException(context: CoroutineContext, exception: Throwable) {
        val log = logger ?: return

        try {
            val stackTrace = StringWriter().also { sw ->
                exception.printStackTrace(PrintWriter(sw))
            }.toString()

            val coroutineName = context[kotlinx.coroutines.CoroutineName]?.name ?: "unknown"
            val job = context[kotlinx.coroutines.Job]

            val attributes = Attributes.builder()
                .put(AttributeKey.stringKey("exception.type"), exception.javaClass.name)
                .put(AttributeKey.stringKey("exception.message"), exception.message ?: "")
                .put(AttributeKey.stringKey("exception.stacktrace"), stackTrace)
                .put(AttributeKey.stringKey("coroutine.name"), coroutineName)
                .put(AttributeKey.booleanKey("coroutine.cancelled"), job?.isCancelled ?: false)
                .put(AttributeKey.stringKey("crash.type"), "coroutine")
                .build()

            log.logRecordBuilder()
                .setSeverity(Severity.ERROR)
                .setBody("Coroutine Crash: ${exception.javaClass.simpleName}: ${exception.message}")
                .setAllAttributes(attributes)
                .emit()
        } catch (e: Exception) {
            // Never crash while handling a crash
        }
    }

    companion object {
        @Volatile
        private var instance: TelemetryCoroutineExceptionHandler? = null

        fun getInstance(): TelemetryCoroutineExceptionHandler {
            return instance ?: synchronized(this) {
                instance ?: TelemetryCoroutineExceptionHandler().also { instance = it }
            }
        }
    }
}
```

**Step 4: Also add necessary imports to CrashHandler.kt**

Add at top of file:
```kotlin
import kotlin.coroutines.CoroutineContext
```

**Step 5: Create ServiceLoader configuration**

```text
# telemetry/src/main/resources/META-INF/services/kotlinx.coroutines.CoroutineExceptionHandler
com.simplecityapps.telemetry.android.TelemetryCoroutineExceptionHandler
```

**Step 6: Run tests to verify they pass**

Run: `./gradlew :telemetry:testDebugUnitTest --tests "*.CoroutineExceptionHandlerTest"`
Expected: PASS

**Step 7: Commit**

```bash
git add telemetry/src/main/kotlin/com/simplecityapps/telemetry/android/CrashHandler.kt \
        telemetry/src/main/resources/META-INF/services/kotlinx.coroutines.CoroutineExceptionHandler \
        telemetry/src/test/kotlin/com/simplecityapps/telemetry/android/CoroutineExceptionHandlerTest.kt
git commit -m "feat: add coroutine exception handler with ServiceLoader"
```

---

### Task 7: Implement ANR Watchdog

**Files:**
- Create: `telemetry/src/main/kotlin/com/simplecityapps/telemetry/android/AnrWatchdog.kt`
- Create: `telemetry/src/test/kotlin/com/simplecityapps/telemetry/android/AnrWatchdogTest.kt`

**Step 1: Write failing test**

```kotlin
// telemetry/src/test/kotlin/com/simplecityapps/telemetry/android/AnrWatchdogTest.kt
package com.simplecityapps.telemetry.android

import android.os.Handler
import android.os.Looper
import io.opentelemetry.api.logs.Logger
import io.opentelemetry.api.logs.LogRecordBuilder
import io.opentelemetry.api.logs.Severity
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertTrue

class AnrWatchdogTest {

    private lateinit var logger: Logger
    private lateinit var logRecordBuilder: LogRecordBuilder

    @Before
    fun setup() {
        logRecordBuilder = mock {
            on { setSeverity(any()) } doReturn it
            on { setBody(any<String>()) } doReturn it
            on { setAllAttributes(any()) } doReturn it
            on { emit() } doAnswer {}
        }
        logger = mock {
            on { logRecordBuilder() } doReturn logRecordBuilder
        }
    }

    @Test
    fun `detects ANR when main thread blocked`() {
        // This is a unit test for the detection logic, not a full integration test
        val watchdog = AnrWatchdog(
            logger = logger,
            timeoutMs = 100, // Short timeout for testing
            checkIntervalMs = 50
        )

        // Simulate blocked main thread by not executing the runnable
        val anrDetected = watchdog.checkForAnr(mainThreadResponded = false)

        assertTrue(anrDetected)
    }

    @Test
    fun `does not detect ANR when main thread responds`() {
        val watchdog = AnrWatchdog(
            logger = logger,
            timeoutMs = 100,
            checkIntervalMs = 50
        )

        val anrDetected = watchdog.checkForAnr(mainThreadResponded = true)

        assertTrue(!anrDetected)
    }

    @Test
    fun `emits log with ERROR severity on ANR`() {
        val watchdog = AnrWatchdog(
            logger = logger,
            timeoutMs = 100,
            checkIntervalMs = 50
        )

        watchdog.reportAnr(Thread.currentThread().stackTrace)

        verify(logRecordBuilder).setSeverity(Severity.ERROR)
        verify(logRecordBuilder).emit()
    }

    @Test
    fun `includes main thread stacktrace in ANR report`() {
        val watchdog = AnrWatchdog(
            logger = logger,
            timeoutMs = 100,
            checkIntervalMs = 50
        )

        val stackTrace = Thread.currentThread().stackTrace
        watchdog.reportAnr(stackTrace)

        verify(logRecordBuilder).setAllAttributes(argThat { attrs ->
            attrs.asMap().any { (key, _) ->
                key.key == "main_thread.stacktrace"
            }
        })
    }
}
```

**Step 2: Run test to verify it fails**

Run: `./gradlew :telemetry:testDebugUnitTest --tests "*.AnrWatchdogTest"`
Expected: FAIL - class not found

**Step 3: Implement AnrWatchdog**

```kotlin
// telemetry/src/main/kotlin/com/simplecityapps/telemetry/android/AnrWatchdog.kt
package com.simplecityapps.telemetry.android

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.Handler
import android.os.Looper
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.logs.Logger
import io.opentelemetry.api.logs.Severity
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal class AnrWatchdog(
    private val logger: Logger,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val checkIntervalMs: Long = DEFAULT_CHECK_INTERVAL_MS
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val watchdogThread: Thread
    private val running = AtomicBoolean(false)
    private val lastResponseTime = AtomicLong(System.currentTimeMillis())
    private val tickReceived = AtomicBoolean(true)

    private val tickRunnable = Runnable {
        tickReceived.set(true)
        lastResponseTime.set(System.currentTimeMillis())
    }

    init {
        watchdogThread = Thread({
            while (running.get()) {
                try {
                    checkMainThread()
                    Thread.sleep(checkIntervalMs)
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    // Continue monitoring
                }
            }
        }, "anr-watchdog")
        watchdogThread.isDaemon = true
    }

    fun start() {
        if (running.compareAndSet(false, true)) {
            lastResponseTime.set(System.currentTimeMillis())
            tickReceived.set(true)
            watchdogThread.start()
        }
    }

    fun stop() {
        running.set(false)
        watchdogThread.interrupt()
    }

    private fun checkMainThread() {
        // Skip if debugger is attached
        if (Debug.isDebuggerConnected()) {
            return
        }

        tickReceived.set(false)
        mainHandler.post(tickRunnable)

        Thread.sleep(timeoutMs)

        if (!tickReceived.get()) {
            // ANR detected - main thread didn't respond within timeout
            onAnrDetected()
        }
    }

    private fun onAnrDetected() {
        // Capture main thread stack trace FIRST
        val mainThread = Looper.getMainLooper().thread
        val mainStackTrace = mainThread.stackTrace

        // Then capture all other threads
        val allStackTraces = Thread.getAllStackTraces()

        reportAnr(mainStackTrace, allStackTraces)
    }

    internal fun checkForAnr(mainThreadResponded: Boolean): Boolean {
        return !mainThreadResponded
    }

    internal fun reportAnr(
        mainStackTrace: Array<StackTraceElement>,
        allStackTraces: Map<Thread, Array<StackTraceElement>> = emptyMap()
    ) {
        val mainStackString = mainStackTrace.joinToString("\n") { "\tat $it" }

        val otherStacksString = allStackTraces
            .filter { it.key != Looper.getMainLooper().thread }
            .entries
            .take(10) // Limit to avoid huge payloads
            .joinToString("\n\n") { (thread, stack) ->
                "Thread: ${thread.name} (${thread.state})\n" +
                    stack.joinToString("\n") { "\tat $it" }
            }

        val attributes = Attributes.builder()
            .put(AttributeKey.stringKey("anr.type"), "watchdog")
            .put(AttributeKey.stringKey("main_thread.stacktrace"), mainStackString)
            .put(AttributeKey.stringKey("other_threads.stacktraces"), otherStacksString)
            .put(AttributeKey.longKey("timeout_ms"), timeoutMs)
            .build()

        logger.logRecordBuilder()
            .setSeverity(Severity.ERROR)
            .setBody("ANR Detected: Main thread blocked for ${timeoutMs}ms")
            .setAllAttributes(attributes)
            .emit()
    }

    companion object {
        private const val DEFAULT_TIMEOUT_MS = 5000L
        private const val DEFAULT_CHECK_INTERVAL_MS = 1000L

        /**
         * Check for historical ANRs on API 30+
         */
        fun checkHistoricalAnrs(context: Context, logger: Logger) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                return
            }

            try {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                    ?: return

                val exitReasons = am.getHistoricalProcessExitReasons(
                    context.packageName,
                    0, // pid 0 = all
                    5  // last 5 exits
                )

                for (info in exitReasons) {
                    if (info.reason == ApplicationExitInfo.REASON_ANR) {
                        reportHistoricalAnr(logger, info)
                    }
                }
            } catch (e: Exception) {
                // Silently ignore
            }
        }

        private fun reportHistoricalAnr(logger: Logger, info: ApplicationExitInfo) {
            val attributes = Attributes.builder()
                .put(AttributeKey.stringKey("anr.type"), "historical")
                .put(AttributeKey.longKey("anr.timestamp"), info.timestamp)
                .put(AttributeKey.stringKey("anr.description"), info.description ?: "")
                .put(AttributeKey.longKey("anr.pid"), info.pid.toLong())
                .build()

            logger.logRecordBuilder()
                .setSeverity(Severity.ERROR)
                .setBody("Historical ANR: ${info.description ?: "No description"}")
                .setAllAttributes(attributes)
                .emit()
        }
    }
}
```

**Step 4: Run tests to verify they pass**

Run: `./gradlew :telemetry:testDebugUnitTest --tests "*.AnrWatchdogTest"`
Expected: PASS

**Step 5: Commit**

```bash
git add telemetry/src/main/kotlin/com/simplecityapps/telemetry/android/AnrWatchdog.kt \
        telemetry/src/test/kotlin/com/simplecityapps/telemetry/android/AnrWatchdogTest.kt
git commit -m "feat: add ANR watchdog with historical ANR detection"
```

---

## Phase 4: Performance Monitoring

### Task 8: Implement Network Interceptor

**Files:**
- Modify: `telemetry/src/main/kotlin/com/simplecityapps/telemetry/android/NetworkInterceptor.kt`
- Create: `telemetry/src/test/kotlin/com/simplecityapps/telemetry/android/NetworkInterceptorTest.kt`

**Step 1: Write failing test**

```kotlin
// telemetry/src/test/kotlin/com/simplecityapps/telemetry/android/NetworkInterceptorTest.kt
package com.simplecityapps.telemetry.android

import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanBuilder
import io.opentelemetry.api.trace.Tracer
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NetworkInterceptorTest {

    @Test
    fun `sanitizes UUIDs in URL path`() {
        val url = "https://api.example.com/users/123e4567-e89b-12d3-a456-426614174000/profile"
        val sanitized = UrlSanitizer.sanitize(url)

        assertEquals("https://api.example.com/users/{id}/profile", sanitized)
    }

    @Test
    fun `sanitizes numeric IDs in URL path`() {
        val url = "https://api.example.com/posts/12345/comments/67890"
        val sanitized = UrlSanitizer.sanitize(url)

        assertEquals("https://api.example.com/posts/{id}/comments/{id}", sanitized)
    }

    @Test
    fun `strips query parameters by default`() {
        val url = "https://api.example.com/search?query=test&page=1&token=secret"
        val sanitized = UrlSanitizer.sanitize(url)

        assertEquals("https://api.example.com/search", sanitized)
    }

    @Test
    fun `identifies sensitive headers`() {
        assertTrue(UrlSanitizer.isSensitiveHeader("Authorization"))
        assertTrue(UrlSanitizer.isSensitiveHeader("Cookie"))
        assertTrue(UrlSanitizer.isSensitiveHeader("Set-Cookie"))
        assertTrue(UrlSanitizer.isSensitiveHeader("X-Api-Key"))
        assertTrue(UrlSanitizer.isSensitiveHeader("X-Auth-Token"))
        assertTrue(UrlSanitizer.isSensitiveHeader("Bearer-Token"))

        assertFalse(UrlSanitizer.isSensitiveHeader("Content-Type"))
        assertFalse(UrlSanitizer.isSensitiveHeader("Accept"))
    }

    @Test
    fun `custom sanitizer overrides default`() {
        val customSanitizer: (String) -> String = { url ->
            url.replace(Regex("/v[0-9]+/"), "/v{version}/")
        }

        val url = "https://api.example.com/v2/users"
        val sanitized = customSanitizer(url)

        assertEquals("https://api.example.com/v{version}/users", sanitized)
    }
}
```

**Step 2: Run test to verify it fails**

Run: `./gradlew :telemetry:testDebugUnitTest --tests "*.NetworkInterceptorTest"`
Expected: FAIL - UrlSanitizer not found

**Step 3: Implement full NetworkInterceptor**

```kotlin
// telemetry/src/main/kotlin/com/simplecityapps/telemetry/android/NetworkInterceptor.kt
package com.simplecityapps.telemetry.android

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

internal class NetworkInterceptor(
    private val urlSanitizerProvider: () -> ((String) -> String)?
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val config = Telemetry.getConfig() ?: return chain.proceed(chain.request())

        val request = chain.request()
        val customSanitizer = urlSanitizerProvider()
        val sanitizedUrl = customSanitizer?.invoke(request.url.toString())
            ?: UrlSanitizer.sanitize(request.url.toString())

        val span = config.tracer.spanBuilder("HTTP ${request.method}")
            .setAttribute("http.method", request.method)
            .setAttribute("http.url", sanitizedUrl)
            .setAttribute("http.target", request.url.encodedPath)
            .startSpan()

        request.body?.contentLength()?.let { size ->
            if (size > 0) {
                span.setAttribute("http.request.body.size", size)
            }
        }

        return try {
            val response = chain.proceed(request)

            span.setAttribute("http.status_code", response.code.toLong())

            response.body?.contentLength()?.let { size ->
                if (size > 0) {
                    span.setAttribute("http.response.body.size", size)
                }
            }

            if (response.code >= 400) {
                span.setStatus(StatusCode.ERROR, "HTTP ${response.code}")
            }

            span.end()
            response
        } catch (e: IOException) {
            span.setStatus(StatusCode.ERROR, e.message ?: "Network error")
            span.recordException(e)
            span.end()
            throw e
        }
    }
}

internal object UrlSanitizer {

    private val UUID_PATTERN = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    private val NUMERIC_ID_PATTERN = Regex("/[0-9]+(?=/|$)")

    private val SENSITIVE_HEADERS = setOf(
        "authorization",
        "cookie",
        "set-cookie",
        "x-api-key"
    )

    private val SENSITIVE_HEADER_PATTERNS = listOf(
        "token",
        "auth",
        "key",
        "secret",
        "password",
        "credential"
    )

    fun sanitize(url: String): String {
        // Strip query parameters
        val urlWithoutQuery = url.substringBefore("?")

        // Replace UUIDs
        var sanitized = urlWithoutQuery.replace(UUID_PATTERN, "{id}")

        // Replace numeric IDs in path segments
        sanitized = sanitized.replace(NUMERIC_ID_PATTERN, "/{id}")

        return sanitized
    }

    fun isSensitiveHeader(headerName: String): Boolean {
        val lower = headerName.lowercase()

        if (lower in SENSITIVE_HEADERS) {
            return true
        }

        return SENSITIVE_HEADER_PATTERNS.any { pattern ->
            lower.contains(pattern)
        }
    }
}
```

**Step 4: Run tests to verify they pass**

Run: `./gradlew :telemetry:testDebugUnitTest --tests "*.NetworkInterceptorTest"`
Expected: PASS

**Step 5: Commit**

```bash
git add telemetry/src/main/kotlin/com/simplecityapps/telemetry/android/NetworkInterceptor.kt \
        telemetry/src/test/kotlin/com/simplecityapps/telemetry/android/NetworkInterceptorTest.kt
git commit -m "feat: add OkHttp interceptor with URL sanitization"
```

---

### Task 9: Implement Startup Tracer

**Files:**
- Create: `telemetry/src/main/kotlin/com/simplecityapps/telemetry/android/StartupTracer.kt`
- Create: `telemetry/src/test/kotlin/com/simplecityapps/telemetry/android/StartupTracerTest.kt`

**Step 1: Write failing test**

```kotlin
// telemetry/src/test/kotlin/com/simplecityapps/telemetry/android/StartupTracerTest.kt
package com.simplecityapps.telemetry.android

import android.app.Activity
import android.app.Application
import android.os.Bundle
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanBuilder
import io.opentelemetry.api.trace.Tracer
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*
import kotlin.test.assertTrue

class StartupTracerTest {

    private lateinit var tracer: Tracer
    private lateinit var spanBuilder: SpanBuilder
    private lateinit var span: Span

    @Before
    fun setup() {
        span = mock {
            on { end() } doAnswer {}
            on { setAttribute(any<String>(), any<Long>()) } doReturn it
        }
        spanBuilder = mock {
            on { startSpan() } doReturn span
            on { setAttribute(any<String>(), any<Long>()) } doReturn it
            on { setAttribute(any<String>(), any<String>()) } doReturn it
        }
        tracer = mock {
            on { spanBuilder(any()) } doReturn spanBuilder
        }
    }

    @Test
    fun `creates startup span on first activity resume`() {
        val startupTracer = StartupTracer(tracer, processStartTime = 1000L)

        // Simulate first activity resume
        startupTracer.onFirstActivityResumed(currentTime = 1500L)

        verify(tracer).spanBuilder("app.startup")
        verify(span).end()
    }

    @Test
    fun `only records first activity resume`() {
        val startupTracer = StartupTracer(tracer, processStartTime = 1000L)

        startupTracer.onFirstActivityResumed(currentTime = 1500L)
        startupTracer.onFirstActivityResumed(currentTime = 2000L)

        // Should only be called once
        verify(tracer, times(1)).spanBuilder("app.startup")
    }

    @Test
    fun `reportFullyDrawn creates full startup span`() {
        val startupTracer = StartupTracer(tracer, processStartTime = 1000L)

        startupTracer.reportFullyDrawn(currentTime = 2000L)

        verify(tracer).spanBuilder("app.startup.full")
        verify(span).end()
    }

    @Test
    fun `reportFullyDrawn only records once`() {
        val startupTracer = StartupTracer(tracer, processStartTime = 1000L)

        startupTracer.reportFullyDrawn(currentTime = 2000L)
        startupTracer.reportFullyDrawn(currentTime = 3000L)

        verify(tracer, times(1)).spanBuilder("app.startup.full")
    }
}
```

**Step 2: Run test to verify it fails**

Run: `./gradlew :telemetry:testDebugUnitTest --tests "*.StartupTracerTest"`
Expected: FAIL - class not found

**Step 3: Implement StartupTracer**

```kotlin
// telemetry/src/main/kotlin/com/simplecityapps/telemetry/android/StartupTracer.kt
package com.simplecityapps.telemetry.android

import android.app.Activity
import android.app.Application
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import io.opentelemetry.api.trace.Tracer
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal class StartupTracer(
    private val tracer: Tracer,
    private val processStartTime: Long = getProcessStartElapsedRealtime()
) : Application.ActivityLifecycleCallbacks {

    private val ttidRecorded = AtomicBoolean(false)
    private val ttfdRecorded = AtomicBoolean(false)

    override fun onActivityResumed(activity: Activity) {
        if (ttidRecorded.compareAndSet(false, true)) {
            onFirstActivityResumed(SystemClock.elapsedRealtime())
        }
    }

    internal fun onFirstActivityResumed(currentTime: Long) {
        if (!ttidRecorded.get()) {
            ttidRecorded.set(true)
        }

        val durationMs = currentTime - processStartTime

        val span = tracer.spanBuilder("app.startup")
            .setAttribute("startup.type", "cold")
            .setAttribute("startup.duration_ms", durationMs)
            .startSpan()

        span.end()
    }

    fun reportFullyDrawn(currentTime: Long = SystemClock.elapsedRealtime()) {
        if (!ttfdRecorded.compareAndSet(false, true)) {
            return
        }

        val durationMs = currentTime - processStartTime

        val span = tracer.spanBuilder("app.startup.full")
            .setAttribute("startup.type", "cold")
            .setAttribute("startup.duration_ms", durationMs)
            .setAttribute("startup.fully_drawn", true)
            .startSpan()

        span.end()
    }

    // Unused lifecycle callbacks
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}

    companion object {
        private fun getProcessStartElapsedRealtime(): Long {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                Process.getStartElapsedRealtime()
            } else {
                // Fallback: use current time minus rough estimate
                SystemClock.elapsedRealtime()
            }
        }

        fun create(tracer: Tracer): StartupTracer {
            return StartupTracer(tracer)
        }
    }
}
```

**Step 4: Run tests to verify they pass**

Run: `./gradlew :telemetry:testDebugUnitTest --tests "*.StartupTracerTest"`
Expected: PASS

**Step 5: Commit**

```bash
git add telemetry/src/main/kotlin/com/simplecityapps/telemetry/android/StartupTracer.kt \
        telemetry/src/test/kotlin/com/simplecityapps/telemetry/android/StartupTracerTest.kt
git commit -m "feat: add startup tracer for TTID and TTFD"
```

---

### Task 10: Implement Lifecycle Tracker

**Files:**
- Create: `telemetry/src/main/kotlin/com/simplecityapps/telemetry/android/LifecycleTracker.kt`
- Create: `telemetry/src/test/kotlin/com/simplecityapps/telemetry/android/LifecycleTrackerTest.kt`

**Step 1: Write failing test**

```kotlin
// telemetry/src/test/kotlin/com/simplecityapps/telemetry/android/LifecycleTrackerTest.kt
package com.simplecityapps.telemetry.android

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import io.opentelemetry.api.logs.Logger
import io.opentelemetry.api.logs.LogRecordBuilder
import io.opentelemetry.api.logs.Severity
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*

class LifecycleTrackerTest {

    private lateinit var logger: Logger
    private lateinit var logRecordBuilder: LogRecordBuilder
    private lateinit var sessionManager: SessionManager

    @Before
    fun setup() {
        logRecordBuilder = mock {
            on { setSeverity(any()) } doReturn it
            on { setBody(any<String>()) } doReturn it
            on { setAllAttributes(any()) } doReturn it
            on { emit() } doAnswer {}
        }
        logger = mock {
            on { logRecordBuilder() } doReturn logRecordBuilder
        }
        sessionManager = mock()
    }

    @Test
    fun `emits foreground event on start`() {
        val tracker = LifecycleTracker(logger, sessionManager, null)

        tracker.onStateChanged(Lifecycle.State.STARTED)

        verify(logRecordBuilder).setBody("app.foreground")
        verify(logRecordBuilder).emit()
        verify(sessionManager).onForeground()
    }

    @Test
    fun `emits background event on stop`() {
        val tracker = LifecycleTracker(logger, sessionManager, null)

        // First go to foreground
        tracker.onStateChanged(Lifecycle.State.STARTED)
        reset(logRecordBuilder, sessionManager)

        // Then background
        whenever(logRecordBuilder.setSeverity(any())).thenReturn(logRecordBuilder)
        whenever(logRecordBuilder.setBody(any<String>())).thenReturn(logRecordBuilder)
        whenever(logRecordBuilder.setAllAttributes(any())).thenReturn(logRecordBuilder)
        whenever(logger.logRecordBuilder()).thenReturn(logRecordBuilder)

        tracker.onStateChanged(Lifecycle.State.CREATED)

        verify(logRecordBuilder).setBody("app.background")
        verify(sessionManager).onBackground()
    }

    @Test
    fun `tracks session duration on background`() {
        val tracker = LifecycleTracker(logger, sessionManager, null)

        // Start foreground
        tracker.onStateChanged(Lifecycle.State.STARTED)
        reset(logRecordBuilder)

        whenever(logRecordBuilder.setSeverity(any())).thenReturn(logRecordBuilder)
        whenever(logRecordBuilder.setBody(any<String>())).thenReturn(logRecordBuilder)
        whenever(logRecordBuilder.setAllAttributes(any())).thenReturn(logRecordBuilder)
        whenever(logger.logRecordBuilder()).thenReturn(logRecordBuilder)

        // Wait a bit and go to background
        Thread.sleep(50)
        tracker.onStateChanged(Lifecycle.State.CREATED)

        verify(logRecordBuilder).setAllAttributes(argThat { attrs ->
            attrs.asMap().any { (key, _) ->
                key.key == "session.duration_ms"
            }
        })
    }
}
```

**Step 2: Run test to verify it fails**

Run: `./gradlew :telemetry:testDebugUnitTest --tests "*.LifecycleTrackerTest"`
Expected: FAIL - class not found

**Step 3: Implement LifecycleTracker**

```kotlin
// telemetry/src/main/kotlin/com/simplecityapps/telemetry/android/LifecycleTracker.kt
package com.simplecityapps.telemetry.android

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.logs.Logger
import io.opentelemetry.api.logs.Severity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

internal class LifecycleTracker(
    private val logger: Logger,
    private val sessionManager: SessionManager,
    private val otelConfig: OtelConfig?
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
        when (state) {
            Lifecycle.State.STARTED, Lifecycle.State.RESUMED -> {
                if (!isInForeground) {
                    isInForeground = true
                    foregroundStartTime = System.currentTimeMillis()
                    onForeground()
                }
            }
            Lifecycle.State.CREATED, Lifecycle.State.INITIALIZED, Lifecycle.State.DESTROYED -> {
                if (isInForeground) {
                    isInForeground = false
                    onBackground()
                }
            }
        }
    }

    private fun onForeground() {
        sessionManager.onForeground()

        emitLifecycleEvent("app.foreground", emptyMap())
    }

    private fun onBackground() {
        val sessionDurationMs = System.currentTimeMillis() - foregroundStartTime

        sessionManager.onBackground()

        emitLifecycleEvent(
            "app.background",
            mapOf("session.duration_ms" to sessionDurationMs)
        )

        // Flush telemetry asynchronously on background
        otelConfig?.let { config ->
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    config.forceFlush()
                } catch (e: Exception) {
                    // Silently ignore flush errors
                }
            }
        }
    }

    private fun emitLifecycleEvent(eventName: String, extraAttributes: Map<String, Any>) {
        val builder = Attributes.builder()
            .put(AttributeKey.stringKey("event.name"), eventName)

        extraAttributes.forEach { (key, value) ->
            when (value) {
                is Long -> builder.put(AttributeKey.longKey(key), value)
                is String -> builder.put(AttributeKey.stringKey(key), value)
                is Boolean -> builder.put(AttributeKey.booleanKey(key), value)
            }
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
            sessionManager: SessionManager,
            otelConfig: OtelConfig?
        ): LifecycleTracker {
            val tracker = LifecycleTracker(logger, sessionManager, otelConfig)
            ProcessLifecycleOwner.get().lifecycle.addObserver(tracker)
            return tracker
        }
    }
}
```

**Step 4: Run tests to verify they pass**

Run: `./gradlew :telemetry:testDebugUnitTest --tests "*.LifecycleTrackerTest"`
Expected: PASS

**Step 5: Commit**

```bash
git add telemetry/src/main/kotlin/com/simplecityapps/telemetry/android/LifecycleTracker.kt \
        telemetry/src/test/kotlin/com/simplecityapps/telemetry/android/LifecycleTrackerTest.kt
git commit -m "feat: add lifecycle tracker with foreground/background events"
```

---

### Task 11: Implement JankStats Tracker

**Files:**
- Create: `telemetry/src/main/kotlin/com/simplecityapps/telemetry/android/JankTracker.kt`
- Create: `telemetry/src/test/kotlin/com/simplecityapps/telemetry/android/JankTrackerTest.kt`

**Step 1: Write failing test**

```kotlin
// telemetry/src/test/kotlin/com/simplecityapps/telemetry/android/JankTrackerTest.kt
package com.simplecityapps.telemetry.android

import io.opentelemetry.api.metrics.LongCounter
import io.opentelemetry.api.metrics.Meter
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*
import kotlin.test.assertEquals

class JankTrackerTest {

    private lateinit var meter: Meter
    private lateinit var totalCounter: LongCounter
    private lateinit var slowCounter: LongCounter
    private lateinit var frozenCounter: LongCounter

    @Before
    fun setup() {
        totalCounter = mock()
        slowCounter = mock()
        frozenCounter = mock()

        val totalBuilder: io.opentelemetry.api.metrics.LongCounterBuilder = mock {
            on { setDescription(any()) } doReturn it
            on { build() } doReturn totalCounter
        }
        val slowBuilder: io.opentelemetry.api.metrics.LongCounterBuilder = mock {
            on { setDescription(any()) } doReturn it
            on { build() } doReturn slowCounter
        }
        val frozenBuilder: io.opentelemetry.api.metrics.LongCounterBuilder = mock {
            on { setDescription(any()) } doReturn it
            on { build() } doReturn frozenCounter
        }

        meter = mock {
            on { counterBuilder("frames.total") } doReturn totalBuilder
            on { counterBuilder("frames.slow") } doReturn slowBuilder
            on { counterBuilder("frames.frozen") } doReturn frozenBuilder
        }
    }

    @Test
    fun `categorizes frame under 16ms as normal`() {
        val aggregator = FrameMetricsAggregator()

        aggregator.recordFrame(durationNanos = 10_000_000) // 10ms

        assertEquals(1, aggregator.totalFrames)
        assertEquals(0, aggregator.slowFrames)
        assertEquals(0, aggregator.frozenFrames)
    }

    @Test
    fun `categorizes frame over 16ms as slow`() {
        val aggregator = FrameMetricsAggregator()

        aggregator.recordFrame(durationNanos = 20_000_000) // 20ms

        assertEquals(1, aggregator.totalFrames)
        assertEquals(1, aggregator.slowFrames)
        assertEquals(0, aggregator.frozenFrames)
    }

    @Test
    fun `categorizes frame over 700ms as frozen`() {
        val aggregator = FrameMetricsAggregator()

        aggregator.recordFrame(durationNanos = 800_000_000) // 800ms

        assertEquals(1, aggregator.totalFrames)
        assertEquals(1, aggregator.slowFrames) // Frozen frames are also slow
        assertEquals(1, aggregator.frozenFrames)
    }

    @Test
    fun `reset clears all counters`() {
        val aggregator = FrameMetricsAggregator()
        aggregator.recordFrame(durationNanos = 800_000_000)

        aggregator.reset()

        assertEquals(0, aggregator.totalFrames)
        assertEquals(0, aggregator.slowFrames)
        assertEquals(0, aggregator.frozenFrames)
    }
}
```

**Step 2: Run test to verify it fails**

Run: `./gradlew :telemetry:testDebugUnitTest --tests "*.JankTrackerTest"`
Expected: FAIL - class not found

**Step 3: Implement JankTracker**

```kotlin
// telemetry/src/main/kotlin/com/simplecityapps/telemetry/android/JankTracker.kt
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

        if (snapshot.totalFrames == 0L) {
            return
        }

        val activityName = currentActivity.get()?.javaClass?.simpleName ?: "unknown"
        val attributes = Attributes.of(
            AttributeKey.stringKey("screen.name"), activityName
        )

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

    // Unused callbacks
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

    @Volatile
    var totalFrames: Long = 0L
        private set

    @Volatile
    var slowFrames: Long = 0L
        private set

    @Volatile
    var frozenFrames: Long = 0L
        private set

    @Synchronized
    fun recordFrame(durationNanos: Long) {
        totalFrames++

        val durationMs = durationNanos / 1_000_000

        if (durationMs > SLOW_THRESHOLD_MS) {
            slowFrames++
        }

        if (durationMs > FROZEN_THRESHOLD_MS) {
            frozenFrames++
        }
    }

    @Synchronized
    fun reset() {
        totalFrames = 0
        slowFrames = 0
        frozenFrames = 0
    }

    @Synchronized
    fun snapshot(): FrameMetricsSnapshot {
        return FrameMetricsSnapshot(totalFrames, slowFrames, frozenFrames)
    }

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
```

**Step 4: Run tests to verify they pass**

Run: `./gradlew :telemetry:testDebugUnitTest --tests "*.JankTrackerTest"`
Expected: PASS

**Step 5: Commit**

```bash
git add telemetry/src/main/kotlin/com/simplecityapps/telemetry/android/JankTracker.kt \
        telemetry/src/test/kotlin/com/simplecityapps/telemetry/android/JankTrackerTest.kt
git commit -m "feat: add JankStats-based frame metrics tracker"
```

---

### Task 12: Implement Navigation Tracker

**Files:**
- Create: `telemetry/src/main/kotlin/com/simplecityapps/telemetry/android/NavigationTracker.kt`
- Create: `telemetry/src/test/kotlin/com/simplecityapps/telemetry/android/NavigationTrackerTest.kt`

**Step 1: Write failing test**

```kotlin
// telemetry/src/test/kotlin/com/simplecityapps/telemetry/android/NavigationTrackerTest.kt
package com.simplecityapps.telemetry.android

import org.junit.Test
import kotlin.test.assertEquals

class NavigationTrackerTest {

    @Test
    fun `normalizes route with UUID argument`() {
        val route = "podcast/123e4567-e89b-12d3-a456-426614174000"
        val normalized = RouteNormalizer.normalize(route)

        assertEquals("podcast/{id}", normalized)
    }

    @Test
    fun `normalizes route with numeric argument`() {
        val route = "user/12345/profile"
        val normalized = RouteNormalizer.normalize(route)

        assertEquals("user/{id}/profile", normalized)
    }

    @Test
    fun `preserves route without arguments`() {
        val route = "home"
        val normalized = RouteNormalizer.normalize(route)

        assertEquals("home", normalized)
    }

    @Test
    fun `handles nested routes with multiple arguments`() {
        val route = "podcast/123/episode/456"
        val normalized = RouteNormalizer.normalize(route)

        assertEquals("podcast/{id}/episode/{id}", normalized)
    }

    @Test
    fun `handles parameterized route templates`() {
        val route = "podcast/{podcastId}/episode/{episodeId}"
        val normalized = RouteNormalizer.normalize(route)

        // Already parameterized, should remain unchanged
        assertEquals("podcast/{podcastId}/episode/{episodeId}", normalized)
    }
}
```

**Step 2: Run test to verify it fails**

Run: `./gradlew :telemetry:testDebugUnitTest --tests "*.NavigationTrackerTest"`
Expected: FAIL - class not found

**Step 3: Implement NavigationTracker**

```kotlin
// telemetry/src/main/kotlin/com/simplecityapps/telemetry/android/NavigationTracker.kt
package com.simplecityapps.telemetry.android

import androidx.navigation.NavController
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.Tracer
import kotlinx.coroutines.flow.collectLatest
import java.util.concurrent.atomic.AtomicReference

internal class NavigationTracker(
    private val tracer: Tracer
) {
    private val currentSpan = AtomicReference<Span?>(null)
    private val currentRoute = AtomicReference<String?>(null)

    suspend fun track(navController: NavController) {
        navController.currentBackStackEntryFlow.collectLatest { entry ->
            val route = entry.destination.route ?: return@collectLatest
            val normalizedRoute = RouteNormalizer.normalize(route)

            // End previous screen span
            currentSpan.get()?.end()

            // Start new screen span
            val span = tracer.spanBuilder("screen.view")
                .setAttribute("screen.name", normalizedRoute)
                .startSpan()

            currentSpan.set(span)
            currentRoute.set(normalizedRoute)
        }
    }

    fun endCurrentSpan() {
        currentSpan.getAndSet(null)?.end()
    }
}

internal object RouteNormalizer {

    private val UUID_PATTERN = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    private val NUMERIC_PATTERN = Regex("(?<=/)\\d+(?=/|$)")
    private val ALREADY_PARAMETERIZED = Regex("\\{[^}]+\\}")

    fun normalize(route: String): String {
        // Don't modify already parameterized routes
        if (ALREADY_PARAMETERIZED.containsMatchIn(route)) {
            return route
        }

        var normalized = route

        // Replace UUIDs
        normalized = normalized.replace(UUID_PATTERN, "{id}")

        // Replace numeric IDs
        normalized = normalized.replace(NUMERIC_PATTERN, "{id}")

        return normalized
    }
}
```

**Step 4: Run tests to verify they pass**

Run: `./gradlew :telemetry:testDebugUnitTest --tests "*.NavigationTrackerTest"`
Expected: PASS

**Step 5: Commit**

```bash
git add telemetry/src/main/kotlin/com/simplecityapps/telemetry/android/NavigationTracker.kt \
        telemetry/src/test/kotlin/com/simplecityapps/telemetry/android/NavigationTrackerTest.kt
git commit -m "feat: add Compose navigation tracker with route normalization"
```

---

## Phase 5: NDK Crash Handling

### Task 13: Implement NdkCrashHandler JNI Bridge

**Files:**
- Create: `telemetry/src/main/kotlin/com/simplecityapps/telemetry/android/NdkCrashHandler.kt`

**Step 1: Implement JNI bridge**

```kotlin
// telemetry/src/main/kotlin/com/simplecityapps/telemetry/android/NdkCrashHandler.kt
package com.simplecityapps.telemetry.android

import android.content.Context
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.logs.Logger
import io.opentelemetry.api.logs.Severity
import java.io.File

internal class NdkCrashHandler(
    private val context: Context,
    private val logger: Logger
) {
    private val crashFile: File = File(context.filesDir, CRASH_FILE_NAME)

    init {
        System.loadLibrary("telemetry")
    }

    fun initialize() {
        // Check for previous crash first
        checkForPreviousCrash()

        // Initialize native handler
        nativeInit(crashFile.absolutePath)
    }

    private fun checkForPreviousCrash() {
        if (!crashFile.exists()) {
            return
        }

        try {
            val crashData = crashFile.readText()
            if (crashData.isNotEmpty()) {
                reportNativeCrash(crashData)
            }
        } catch (e: Exception) {
            // Silently ignore read errors
        } finally {
            crashFile.delete()
        }
    }

    private fun reportNativeCrash(crashData: String) {
        val lines = crashData.lines()
        val signal = lines.getOrNull(0) ?: "unknown"
        val faultAddress = lines.getOrNull(1) ?: "unknown"
        val backtrace = lines.drop(2).joinToString("\n")

        val attributes = Attributes.builder()
            .put(AttributeKey.stringKey("crash.type"), "native")
            .put(AttributeKey.stringKey("signal"), signal)
            .put(AttributeKey.stringKey("fault.address"), faultAddress)
            .put(AttributeKey.stringKey("backtrace"), backtrace)
            .build()

        logger.logRecordBuilder()
            .setSeverity(Severity.FATAL)
            .setBody("Native Crash: $signal")
            .setAllAttributes(attributes)
            .emit()
    }

    private external fun nativeInit(crashFilePath: String)

    companion object {
        private const val CRASH_FILE_NAME = "native_crash.txt"
    }
}
```

**Step 2: Commit**

```bash
git add telemetry/src/main/kotlin/com/simplecityapps/telemetry/android/NdkCrashHandler.kt
git commit -m "feat: add NDK crash handler JNI bridge"
```

---

### Task 14: Implement Native Crash Handler (C)

**Files:**
- Modify: `telemetry/src/main/cpp/ndk_crash_handler.c`
- Create: `telemetry/src/main/cpp/ndk_crash_handler.h`

**Step 1: Create header file**

```c
// telemetry/src/main/cpp/ndk_crash_handler.h
#ifndef NDK_CRASH_HANDLER_H
#define NDK_CRASH_HANDLER_H

#include <signal.h>

// Pre-allocated buffer sizes (async-signal-safe requirement)
#define MAX_BACKTRACE_DEPTH 64
#define CRASH_BUFFER_SIZE 4096
#define INT_BUFFER_SIZE 24

// Signal info
typedef struct {
    int signal;
    const char* name;
} signal_info_t;

// Initialize crash handler with file path for crash data
void crash_handler_init(const char* crash_file_path);

#endif // NDK_CRASH_HANDLER_H
```

**Step 2: Implement full crash handler**

```c
// telemetry/src/main/cpp/ndk_crash_handler.c
#include <jni.h>
#include <signal.h>
#include <unistd.h>
#include <fcntl.h>
#include <string.h>
#include <ucontext.h>
#include <dlfcn.h>

// For backtrace
#include <unwind.h>

#include "ndk_crash_handler.h"

// Pre-allocated buffers (MUST be allocated before any signal)
static char g_crash_file_path[256];
static int g_crash_fd = -1;
static char g_write_buffer[CRASH_BUFFER_SIZE];
static void* g_backtrace_buffer[MAX_BACKTRACE_DEPTH];

// Previous signal handlers to chain
static struct sigaction g_old_handlers[6];

// Signals to handle
static const int g_signals[] = {SIGSEGV, SIGABRT, SIGBUS, SIGFPE, SIGILL, SIGTRAP};
static const int g_num_signals = 6;

// Signal names (async-signal-safe - no allocations)
static const char* get_signal_name(int sig) {
    switch (sig) {
        case SIGSEGV: return "SIGSEGV";
        case SIGABRT: return "SIGABRT";
        case SIGBUS:  return "SIGBUS";
        case SIGFPE:  return "SIGFPE";
        case SIGILL:  return "SIGILL";
        case SIGTRAP: return "SIGTRAP";
        default:      return "UNKNOWN";
    }
}

// Async-signal-safe integer to string (no sprintf!)
static int safe_itoa(unsigned long value, char* buffer, int base) {
    static const char digits[] = "0123456789abcdef";
    char temp[INT_BUFFER_SIZE];
    int i = 0;
    int j = 0;

    if (value == 0) {
        buffer[0] = '0';
        buffer[1] = '\0';
        return 1;
    }

    while (value > 0 && i < INT_BUFFER_SIZE - 1) {
        temp[i++] = digits[value % base];
        value /= base;
    }

    // Reverse
    while (i > 0) {
        buffer[j++] = temp[--i];
    }
    buffer[j] = '\0';

    return j;
}

// Async-signal-safe string copy
static int safe_strcpy(char* dest, const char* src, int max_len) {
    int i = 0;
    while (src[i] != '\0' && i < max_len - 1) {
        dest[i] = src[i];
        i++;
    }
    dest[i] = '\0';
    return i;
}

// Async-signal-safe write wrapper
static void safe_write(int fd, const char* str) {
    if (fd < 0 || str == NULL) return;
    size_t len = 0;
    while (str[len] != '\0') len++;
    write(fd, str, len);
}

// Backtrace state for _Unwind_Backtrace
typedef struct {
    void** buffer;
    int max_depth;
    int count;
} backtrace_state_t;

static _Unwind_Reason_Code unwind_callback(struct _Unwind_Context* context, void* arg) {
    backtrace_state_t* state = (backtrace_state_t*)arg;

    if (state->count >= state->max_depth) {
        return _URC_END_OF_STACK;
    }

    uintptr_t pc = _Unwind_GetIP(context);
    if (pc != 0) {
        state->buffer[state->count++] = (void*)pc;
    }

    return _URC_NO_REASON;
}

static int capture_backtrace(void** buffer, int max_depth) {
    backtrace_state_t state = {buffer, max_depth, 0};
    _Unwind_Backtrace(unwind_callback, &state);
    return state.count;
}

// Signal handler - MUST be async-signal-safe
static void crash_signal_handler(int sig, siginfo_t* info, void* ucontext) {
    // Open crash file (pre-path was stored at init)
    int fd = open(g_crash_file_path, O_WRONLY | O_CREAT | O_TRUNC, 0644);
    if (fd < 0) {
        fd = g_crash_fd; // Fallback to pre-opened fd
    }

    if (fd >= 0) {
        char num_buf[INT_BUFFER_SIZE];

        // Write signal name
        safe_write(fd, get_signal_name(sig));
        safe_write(fd, "\n");

        // Write fault address
        safe_write(fd, "0x");
        safe_itoa((unsigned long)info->si_addr, num_buf, 16);
        safe_write(fd, num_buf);
        safe_write(fd, "\n");

        // Capture and write backtrace
        int depth = capture_backtrace(g_backtrace_buffer, MAX_BACKTRACE_DEPTH);
        for (int i = 0; i < depth; i++) {
            safe_write(fd, "0x");
            safe_itoa((unsigned long)g_backtrace_buffer[i], num_buf, 16);
            safe_write(fd, num_buf);
            safe_write(fd, "\n");
        }

        // Close if we opened a new fd
        if (fd != g_crash_fd) {
            close(fd);
        }
    }

    // Re-raise signal with original handler
    int sig_index = -1;
    for (int i = 0; i < g_num_signals; i++) {
        if (g_signals[i] == sig) {
            sig_index = i;
            break;
        }
    }

    if (sig_index >= 0) {
        // Restore old handler and re-raise
        sigaction(sig, &g_old_handlers[sig_index], NULL);
    }

    raise(sig);
}

void crash_handler_init(const char* crash_file_path) {
    // Store path (pre-allocate)
    safe_strcpy(g_crash_file_path, crash_file_path, sizeof(g_crash_file_path));

    // Pre-open crash file descriptor as fallback
    g_crash_fd = open(crash_file_path, O_WRONLY | O_CREAT | O_TRUNC, 0644);

    // Set up signal handlers
    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_sigaction = crash_signal_handler;
    sa.sa_flags = SA_SIGINFO | SA_ONSTACK;
    sigemptyset(&sa.sa_mask);

    for (int i = 0; i < g_num_signals; i++) {
        sigaction(g_signals[i], &sa, &g_old_handlers[i]);
    }
}

// JNI entry point
JNIEXPORT void JNICALL
Java_com_simplecityapps_telemetry_android_NdkCrashHandler_nativeInit(
    JNIEnv *env,
    jobject thiz,
    jstring crash_file_path
) {
    const char* path = (*env)->GetStringUTFChars(env, crash_file_path, NULL);
    if (path != NULL) {
        crash_handler_init(path);
        (*env)->ReleaseStringUTFChars(env, crash_file_path, path);
    }
}
```

**Step 3: Verify native build**

Run: `./gradlew :telemetry:assembleDebug`
Expected: BUILD SUCCESSFUL (native code compiles)

**Step 4: Commit**

```bash
git add telemetry/src/main/cpp/ndk_crash_handler.c \
        telemetry/src/main/cpp/ndk_crash_handler.h
git commit -m "feat: add async-signal-safe native crash handler"
```

---

## Phase 6: Integration

### Task 15: Wire Up All Collectors in Telemetry.init()

**Files:**
- Modify: `telemetry/src/main/kotlin/com/simplecityapps/telemetry/android/Telemetry.kt`

**Step 1: Update initializeCollectors in Telemetry.kt**

Replace the `initializeCollectors` method:

```kotlin
private fun initializeCollectors(application: Application) {
    val config = configRef.get() ?: return
    val sessionManager = sessionManagerRef.get() ?: return

    // JVM crash handler
    CrashHandler.install(config.logger)

    // Coroutine exception handler
    TelemetryCoroutineExceptionHandler.getInstance().setLogger(config.logger)

    // ANR watchdog
    val anrWatchdog = AnrWatchdog(config.logger)
    anrWatchdog.start()
    AnrWatchdog.checkHistoricalAnrs(application, config.logger)

    // Startup tracer
    val startupTracer = StartupTracer.create(config.tracer)
    startupTracerRef.set(startupTracer)
    application.registerActivityLifecycleCallbacks(startupTracer)

    // Lifecycle tracker
    LifecycleTracker.register(config.logger, sessionManager, config)

    // Jank tracker
    val jankTracker = JankTracker(config.meter, telemetryScope)
    application.registerActivityLifecycleCallbacks(jankTracker)
    jankTracker.start()

    // Navigation tracker
    navigationTrackerRef.set(NavigationTracker(config.tracer))

    // NDK crash handler
    try {
        val ndkHandler = NdkCrashHandler(application, config.logger)
        ndkHandler.initialize()
    } catch (e: UnsatisfiedLinkError) {
        // Native library not available - skip NDK crash handling
        android.util.Log.w(TAG, "NDK crash handler not available", e)
    }
}
```

**Step 2: Add missing fields and update methods in Telemetry.kt**

Add these fields after existing AtomicReferences:

```kotlin
private val startupTracerRef = AtomicReference<StartupTracer?>(null)
private val navigationTrackerRef = AtomicReference<NavigationTracker?>(null)
```

Update `reportFullyDrawn`:

```kotlin
fun reportFullyDrawn() {
    startupTracerRef.get()?.reportFullyDrawn()
}
```

Update `trackNavigation`:

```kotlin
suspend fun trackNavigation(navController: NavController) {
    navigationTrackerRef.get()?.track(navController)
}
```

**Step 3: Verify full build**

Run: `./gradlew :telemetry:assembleDebug`
Expected: BUILD SUCCESSFUL

**Step 4: Run all tests**

Run: `./gradlew :telemetry:testDebugUnitTest`
Expected: All tests PASS

**Step 5: Commit**

```bash
git add telemetry/src/main/kotlin/com/simplecityapps/telemetry/android/Telemetry.kt
git commit -m "feat: wire up all collectors in Telemetry.init()"
```

---

### Task 16: Add Gradle Wrapper

**Files:**
- Run gradle wrapper command

**Step 1: Generate gradle wrapper**

Run: `gradle wrapper --gradle-version 8.5`

**Step 2: Verify wrapper works**

Run: `./gradlew :telemetry:assembleDebug`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add gradle/ gradlew gradlew.bat
git commit -m "chore: add Gradle wrapper"
```

---

### Task 17: Final Verification

**Step 1: Run full build**

Run: `./gradlew clean :telemetry:assembleRelease`
Expected: BUILD SUCCESSFUL

**Step 2: Run all unit tests**

Run: `./gradlew :telemetry:testReleaseUnitTest`
Expected: All tests PASS

**Step 3: Check AAR output**

Run: `ls -la telemetry/build/outputs/aar/`
Expected: `telemetry-release.aar` exists

**Step 4: Final commit**

```bash
git add .
git commit -m "chore: final build verification"
```

---

## Summary

**Total Tasks:** 17
**Estimated Lines of Code:** ~1500 Kotlin + ~250 C

**Test Coverage:**
- SessionManager: session ID generation, timeout logic
- OtelConfig: SDK initialization
- Telemetry: public API, init flow
- CrashHandler: exception capture, chaining
- CoroutineExceptionHandler: context capture
- AnrWatchdog: detection logic, reporting
- NetworkInterceptor: URL sanitization, header filtering
- StartupTracer: TTID/TTFD recording
- LifecycleTracker: foreground/background events
- JankTracker: frame categorization
- NavigationTracker: route normalization

**Key Integration Points:**
- All collectors initialized async in `Telemetry.init()`
- Crash handlers emit logs synchronously before process death
- Frame metrics aggregated and reported every 30s
- Session ID persisted across process death with 30-min timeout
