package com.owldev.adsignage

/**
 * AC 11 — Resolves the player page URL the WebView will load.
 *
 * The contract is intentionally narrow:
 *   - input: a base URL (e.g. https://stream.owl-dev.me) + a device_id (UUID)
 *   - output: "${base}/player/${deviceId}"
 *
 * The base URL is sourced from a string resource (`R.string.player_base_url`)
 * so QA / staging / prod can be swapped without touching code, and any
 * trailing slashes are trimmed. The device_id is required and must be
 * non-blank — passing a blank id is a programming error and we fail fast
 * rather than letting the WebView load `…/player/` and silently 404.
 *
 * Kept as a pure object (no Android imports) so it's exercisable from a
 * JVM unit test without Robolectric — the URL contract is the part most
 * likely to drift across deploys, so we want it covered by the cheapest
 * possible tests.
 */
object PlayerUrl {

    private const val PLAYER_PATH = "player"

    /**
     * Returns the canonical player URL for [deviceId] under [baseUrl].
     *
     * @throws IllegalArgumentException if [baseUrl] or [deviceId] is blank.
     */
    fun forDevice(baseUrl: String, deviceId: String): String {
        require(baseUrl.isNotBlank()) { "baseUrl must not be blank" }
        require(deviceId.isNotBlank()) { "deviceId must not be blank" }

        val trimmedBase = baseUrl.trim().trimEnd('/')
        val trimmedDevice = deviceId.trim()

        return "$trimmedBase/$PLAYER_PATH/$trimmedDevice"
    }
}
