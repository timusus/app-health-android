package com.simplecityapps.telemetry.android

import org.junit.Test
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
