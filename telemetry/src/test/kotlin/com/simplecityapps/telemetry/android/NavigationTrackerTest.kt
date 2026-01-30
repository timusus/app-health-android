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

        assertEquals("podcast/{podcastId}/episode/{episodeId}", normalized)
    }
}
