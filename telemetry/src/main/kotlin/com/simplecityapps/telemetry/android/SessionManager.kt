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
        val sessionExpired = lastBackground > 0 && (now - lastBackground) > SESSION_TIMEOUT_MS

        val sessionId = if (existingId == null || sessionExpired) {
            UUID.randomUUID().toString().also { prefs.edit().putString(KEY_SESSION_ID, it).apply() }
        } else {
            existingId
        }

        _sessionId.set(sessionId)
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
