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
