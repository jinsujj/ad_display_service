package me.owldev.adsignage.sse

import java.time.Instant

/**
 * Wire-format constants and DTOs for SSE events sent to player pages.
 *
 * Maps the ontology concepts:
 *  - sse_event_type → [EventType] enum values
 *  - sse_payload    → the data classes below (one per event type)
 *
 * The Next.js player at `/player/{deviceId}` matches on the `event:` SSE
 * field and parses `data:` as JSON of the corresponding payload type.
 */
object SseEventNames {
    /** Sent once immediately after the player connects, so the client
     *  can confirm the channel is healthy and log the deviceId server-side. */
    const val CONNECTED = "CONNECTED"

    /** Sent when an admin remaps a device to a different restaurant —
     *  triggers the player to fetch a new playlist for the new restaurant. */
    const val MAPPING_CHANGED = "MAPPING_CHANGED"

    /** Sent when a device's playlist has changed for any other reason
     *  (e.g. an advertiser added / scheduled a new ad on the same restaurant).
     *  Reserved for sibling sub-ACs; defined here to keep the event vocabulary
     *  in one place. */
    const val PLAYLIST_UPDATE = "PLAYLIST_UPDATE"
}

/**
 * Payload for [SseEventNames.CONNECTED].
 *
 * Lightweight handshake — the player uses this to confirm the SSE pipe is
 * live before deciding whether to fall back to polling.
 */
data class ConnectedPayload(
    val deviceId: String,
    val serverTime: Instant = Instant.now(),
)

/**
 * Payload for [SseEventNames.MAPPING_CHANGED].
 *
 * Carries the post-remap state so the player can short-circuit a separate
 * GET — the new restaurantId is already in hand. The player should still
 * fetch the playlist for the new restaurant; this payload exists so the
 * player can render a "Switching to {restaurant}…" splash immediately
 * without waiting on the playlist round-trip.
 *
 * Ontology mapping:
 *  - deviceId        → device_id
 *  - restaurantId    → device_restaurant_id (after remap)
 *  - assignmentId    → (audit handle for this active row)
 *  - assignedAt      → when the new mapping took effect
 */
data class MappingChangedPayload(
    val deviceId: String,
    val restaurantId: String,
    val assignmentId: String,
    val assignedAt: Instant,
)

/**
 * Payload for [SseEventNames.PLAYLIST_UPDATE] (sub-AC 50003.3).
 *
 * Sent by [PlaylistEventPublisher.publishPlaylistUpdated] when the device's
 * playlist contents changed for any reason other than a restaurant remap
 * (e.g. an advertiser added a new ad, edited a schedule, deleted an ad
 * already on this device's restaurant).
 *
 * Mirrors the TypeScript wire contract `PlaylistUpdatePayload` declared in
 * `web/hooks/usePlayerSse.ts` so the player can decode this payload without
 * a coordinated client roll. The frontend's tolerant parser accepts unknown
 * extra keys and missing optional fields, but it always requires `deviceId`.
 *
 * Forward-compat fields:
 *  - [restaurantId] echoed for correlation; the player uses this to
 *    sanity-check that the event was routed to the right device.
 *  - [updatedAt] ISO-8601 timestamp of when the schedule changed; lets the
 *    consumer ignore an out-of-order event.
 *  - [playlist] reserved for inline playlists (avoids a refetch round-trip).
 *    Currently `null` — sibling sub-ACs will populate it once the playlist
 *    builder is wired in. Typed as `Any?` so this module stays decoupled
 *    from the playlist-build module.
 */
data class PlaylistUpdatedPayload(
    val deviceId: String,
    val restaurantId: String? = null,
    val updatedAt: Instant? = Instant.now(),
    val playlist: Any? = null,
)
