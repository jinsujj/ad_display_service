package com.owldev.adsignage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.UUID

/**
 * Verifies AC 11: the WebView loads `https://stream.owl-dev.me/player/{deviceId}`.
 *
 * Pure JVM unit test — no Robolectric — because the URL contract is the
 * thing most likely to drift across environments and we want it covered
 * by the cheapest possible test layer.
 */
class PlayerUrlTest {

    private val baseUrl = "https://stream.owl-dev.me"
    private val deviceId = "11111111-2222-3333-4444-555555555555"

    @Test
    fun `forDevice composes base url with player path and device id`() {
        val url = PlayerUrl.forDevice(baseUrl, deviceId)
        assertEquals("https://stream.owl-dev.me/player/$deviceId", url)
    }

    @Test
    fun `forDevice trims a single trailing slash from base url`() {
        val url = PlayerUrl.forDevice("$baseUrl/", deviceId)
        assertEquals("https://stream.owl-dev.me/player/$deviceId", url)
    }

    @Test
    fun `forDevice trims multiple trailing slashes from base url`() {
        val url = PlayerUrl.forDevice("$baseUrl///", deviceId)
        assertEquals("https://stream.owl-dev.me/player/$deviceId", url)
    }

    @Test
    fun `forDevice trims surrounding whitespace from base url`() {
        val url = PlayerUrl.forDevice("  $baseUrl  ", deviceId)
        assertEquals("https://stream.owl-dev.me/player/$deviceId", url)
    }

    @Test
    fun `forDevice accepts a real UUID v4 and round-trips through UUID parser`() {
        val realId = UUID.randomUUID().toString()
        val url = PlayerUrl.forDevice(baseUrl, realId)
        assertEquals("https://stream.owl-dev.me/player/$realId", url)
    }

    @Test
    fun `forDevice supports staging or QA base urls without code change`() {
        val staging = "https://stream-staging.owl-dev.me"
        val url = PlayerUrl.forDevice(staging, deviceId)
        assertEquals("$staging/player/$deviceId", url)
    }

    @Test
    fun `forDevice rejects blank deviceId`() {
        assertThrows(IllegalArgumentException::class.java) {
            PlayerUrl.forDevice(baseUrl, "")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PlayerUrl.forDevice(baseUrl, "   ")
        }
    }

    @Test
    fun `forDevice rejects blank baseUrl`() {
        assertThrows(IllegalArgumentException::class.java) {
            PlayerUrl.forDevice("", deviceId)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PlayerUrl.forDevice("   ", deviceId)
        }
    }

    @Test
    fun `forDevice does not double-slash when base url has no trailing slash`() {
        val url = PlayerUrl.forDevice(baseUrl, deviceId)
        // Exactly one separator between host and `player`, exactly one
        // between `player` and the device id.
        val schemeStripped = url.removePrefix("https://")
        assertEquals(2, schemeStripped.count { it == '/' })
    }
}
