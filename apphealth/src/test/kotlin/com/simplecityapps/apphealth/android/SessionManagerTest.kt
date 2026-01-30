package com.simplecityapps.apphealth.android

import android.content.Context
import com.simplecityapps.apphealth.android.fakes.FakeSharedPreferences
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
    fun `onForeground regenerates session after timeout`() {
        // Setup: existing session
        val existingSessionId = UUID.randomUUID().toString()
        prefs.edit()
            .putString("session_id", existingSessionId)
            .apply()

        val manager = SessionManager(context)
        assertEquals(existingSessionId, manager.sessionId)

        // Simulate 31 minutes in background
        val thirtyOneMinutesAgo = System.currentTimeMillis() - (31 * 60 * 1000)
        prefs.edit()
            .putLong("last_background_time", thirtyOneMinutesAgo)
            .apply()

        manager.onForeground()

        // Session should be regenerated
        assertNotEquals(existingSessionId, manager.sessionId)
    }

    @Test
    fun `generates valid UUID installation ID on first launch`() {
        val manager = SessionManager(context)

        val installationId = manager.installationId
        assertNotNull(installationId)
        UUID.fromString(installationId) // Validates it's a proper UUID
    }

    @Test
    fun `persists installation ID to shared preferences`() {
        val manager = SessionManager(context)

        val storedId = prefs.getString("installation_id", null)
        assertEquals(manager.installationId, storedId)
    }

    @Test
    fun `returns same installation ID on subsequent launches`() {
        // First launch
        val manager1 = SessionManager(context)
        val firstInstallationId = manager1.installationId

        // Simulate app restart (new SessionManager with same prefs)
        val manager2 = SessionManager(context)
        val secondInstallationId = manager2.installationId

        assertEquals(firstInstallationId, secondInstallationId)
    }

    @Test
    fun `installation ID is independent of session ID`() {
        val manager = SessionManager(context)

        // Installation ID should be different from session ID
        assertNotEquals(manager.installationId, manager.sessionId)
    }
}
