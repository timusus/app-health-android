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
    fun `logEvent does not throw before init`() {
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
    }

    @Test
    fun `setAttribute does not throw`() {
        Telemetry.init(
            context = application,
            endpoint = "http://localhost:4318",
            serviceName = "test-app"
        )

        Telemetry.setAttribute("tier", "premium")
    }
}
