package com.simplecityapps.apphealth.android

import android.app.Application
import android.content.SharedPreferences
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import com.simplecityapps.apphealth.android.fakes.InMemoryTelemetry
import io.opentelemetry.api.OpenTelemetry
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AppHealthTest {

    private lateinit var application: Application
    private lateinit var prefs: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor
    private lateinit var packageManager: PackageManager
    private lateinit var telemetry: InMemoryTelemetry

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

        telemetry = InMemoryTelemetry()
        AppHealth.reset()
    }

    @After
    fun teardown() {
        AppHealth.reset()
        telemetry.shutdown()
    }

    @Test
    fun `init returns immediately without blocking`() {
        val startTime = System.currentTimeMillis()

        AppHealth.init(
            context = application,
            openTelemetry = telemetry.openTelemetry
        )

        val duration = System.currentTimeMillis() - startTime
        assertTrue(duration < 100, "init should return quickly, took ${duration}ms")
    }

    @Test
    fun `isInitialized returns true after init called`() {
        assertFalse(AppHealth.isInitialized)

        AppHealth.init(
            context = application,
            openTelemetry = telemetry.openTelemetry
        )

        assertTrue(AppHealth.isInitialized)
    }

    @Test
    fun `awaitReady blocks until initialization completes`() {
        AppHealth.init(
            context = application,
            openTelemetry = telemetry.openTelemetry
        )

        // awaitReady should block and then return (true or false depending on init success)
        // With mocked Application, collectors may fail to init, but the latch should still count down
        val startTime = System.currentTimeMillis()
        AppHealth.awaitReady(timeoutMs = 5000)
        val elapsed = System.currentTimeMillis() - startTime

        // Verify it didn't just timeout - init should complete quickly with mocks
        assertTrue(elapsed < 4000, "awaitReady should complete before timeout, took ${elapsed}ms")
    }

    @Test
    fun `awaitReady returns false before init called`() {
        // Don't call init
        assertFalse(AppHealth.isReady)

        // awaitReady with no latch should return isReady (false)
        val ready = AppHealth.awaitReady(timeoutMs = 100)
        assertFalse(ready)
    }

    @Test
    fun `forceFlush returns false before ready`() {
        // Don't call init
        val flushed = AppHealth.forceFlush()

        assertFalse(flushed, "forceFlush should return false when not initialized")
    }

    @Test
    fun `config DSL disables collectors`() {
        var configCaptured: AppHealthConfig? = null

        AppHealth.init(
            context = application,
            openTelemetry = telemetry.openTelemetry
        ) {
            crashHandling = false
            anrDetection = false
            ndkCrashHandling = false
            configCaptured = this
        }

        assertNotNull(configCaptured)
        assertFalse(configCaptured!!.crashHandling)
        assertFalse(configCaptured!!.anrDetection)
        assertFalse(configCaptured!!.ndkCrashHandling)
        assertTrue(configCaptured!!.lifecycleTracking) // default true
    }

    @Test
    fun `network sampling config is applied`() {
        var configCaptured: AppHealthConfig? = null

        AppHealth.init(
            context = application,
            openTelemetry = telemetry.openTelemetry
        ) {
            networkSampling {
                successSampleRate = 0.5
                maxErrorsPerMinute = 20
            }
            configCaptured = this
        }

        assertNotNull(configCaptured)
        assertEquals(0.5, configCaptured!!.networkSampling.successSampleRate)
        assertEquals(20, configCaptured!!.networkSampling.maxErrorsPerMinute)
    }

    @Test
    fun `custom url sanitizer is stored`() {
        val customSanitizer: (String) -> String = { url -> url.replace("/v1/", "/v{n}/") }
        var configCaptured: AppHealthConfig? = null

        AppHealth.init(
            context = application,
            openTelemetry = telemetry.openTelemetry
        ) {
            urlSanitizer = customSanitizer
            configCaptured = this
        }

        assertNotNull(configCaptured!!.urlSanitizer)
        assertEquals("/api/v{n}/users", configCaptured!!.urlSanitizer!!("/api/v1/users"))
    }

    @Test
    fun `forceFlush returns true when SDK is ready`() {
        AppHealth.init(
            context = application,
            openTelemetry = telemetry.openTelemetry
        )
        AppHealth.awaitReady()

        val flushed = AppHealth.forceFlush()

        assertTrue(flushed, "forceFlush should return true when SDK is ready")
    }

    @Test
    fun `noop OpenTelemetry is handled gracefully`() {
        AppHealth.init(
            context = application,
            openTelemetry = OpenTelemetry.noop()
        )
        AppHealth.awaitReady()

        // forceFlush should succeed even with noop (just does nothing)
        val flushed = AppHealth.forceFlush()
        assertTrue(flushed)
    }
}
