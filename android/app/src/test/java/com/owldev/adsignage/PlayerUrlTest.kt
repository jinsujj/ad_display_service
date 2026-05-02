package com.owldev.adsignage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.UUID

/**
 * AC 11 검증: WebView가 `https://stream.owl-dev.me/player/{deviceId}`를 로드한다.
 *
 * 순수 JVM 단위 테스트 — Robolectric을 사용하지 않음 — 이는 환경에 따라
 * 가장 어긋나기 쉬운 부분이 URL 계약이므로, 가장 저렴한 테스트 계층으로
 * 커버하기 위함이다.
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
        // 호스트와 `player` 사이는 정확히 슬래시 하나, `player`와 device id
        // 사이도 정확히 슬래시 하나여야 한다.
        val schemeStripped = url.removePrefix("https://")
        assertEquals(2, schemeStripped.count { it == '/' })
    }
}
