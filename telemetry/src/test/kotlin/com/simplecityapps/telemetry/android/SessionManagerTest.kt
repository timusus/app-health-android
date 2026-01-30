package com.simplecityapps.telemetry.android

import android.content.Context
import com.simplecityapps.telemetry.android.fakes.FakeSharedPreferences
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*
import java.util.UUID
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals
import kotlin.test.assertEquals

class SessionManagerTest {

    private lateinit var context: Context
    private lateinit var prefs: FakeSharedPreferences

    @Before
    fun setup() {
        prefs = FakeSharedPreferences()
        context = mock {
            on { getSharedPreferences(any(), any()) } doReturn prefs
            on { packageName } doReturn "com.test.app"
            on { applicationContext } doReturn it
        }
    }

    @Test
    fun `generates valid UUID session ID on cold start`() {
        val manager = SessionManager(context)

        val sessionId = manager.sessionId
        assertNotNull(sessionId)
        UUID.fromString(sessionId) // Validates it's a proper UUID
    }

    @Test
    fun `persists session ID to shared preferences`() {
        val manager = SessionManager(context)

        val storedId = prefs.getString("session_id", null)
        assertEquals(manager.sessionId, storedId)
    }

    @Test
    fun `regenerates session ID after 30 minute timeout`() {
        // Setup: existing session from 31 minutes ago
        val existingSessionId = UUID.randomUUID().toString()
        val thirtyOneMinutesAgo = System.currentTimeMillis() - (31 * 60 * 1000)
        prefs.edit()
            .putString("session_id", existingSessionId)
            .putLong("last_background_time", thirtyOneMinutesAgo)
            .apply()

        val manager = SessionManager(context)

        // Session ID should be different (regenerated due to timeout)
        assertNotEquals(existingSessionId, manager.sessionId)
    }

    @Test
    fun `keeps session ID if under 30 minute timeout`() {
        // Setup: existing session from 10 minutes ago
        val existingSessionId = UUID.randomUUID().toString()
        val tenMinutesAgo = System.currentTimeMillis() - (10 * 60 * 1000)
        prefs.edit()
            .putString("session_id", existingSessionId)
            .putLong("last_background_time", tenMinutesAgo)
            .apply()

        val manager = SessionManager(context)

        // Session ID should be the same (not timed out)
        assertEquals(existingSessionId, manager.sessionId)
    }

    @Test
    fun `records background timestamp on background`() {
        val manager = SessionManager(context)
        val beforeBackground = System.currentTimeMillis()

        manager.onBackground()

        val storedTimestamp = prefs.getLong("last_background_time", 0L)
        assert(storedTimestamp >= beforeBackground)
    }

    @Test
    fun `stores and retrieves user ID`() {
        val manager = SessionManager(context)

        manager.setUserId("user-123")

        assertEquals("user-123", manager.userId)
    }

    @Test
    fun `stores and retrieves custom attributes`() {
        val manager = SessionManager(context)

        manager.setAttribute("tier", "premium")
        manager.setAttribute("region", "us-west")

        assertEquals("premium", manager.customAttributes["tier"])
        assertEquals("us-west", manager.customAttributes["region"])
    }
}
