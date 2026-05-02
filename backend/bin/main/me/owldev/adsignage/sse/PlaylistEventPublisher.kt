package me.owldev.adsignage.sse

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

/**
 * Sub-AC 50003.3 — wire-side publisher for "playlist-updated" SSE events.
 *
 * # Responsibility
 * Retrieve every live emitter for a `deviceId` from [SseEmitterRegistry]
 * and broadcast a single SSE event so every connected player page for that
 * device re-fetches (or applies inline) the new playlist.
 *
 * # Why a dedicated publisher (instead of inlining `registry.broadcast`)
 *  - Keeps domain code (e.g. `AdService.create`, `ScheduleService.update`)
 *    free of SSE plumbing — they call one typed method.
 *  - Centralises the `name(...) + .data(...)` event-builder shape so the
 *    wire contract for `PLAYLIST_UPDATE` lives in exactly one place.
 *  - Lets later ACs add cross-cutting concerns (rate-limiting, audit log,
 *    metrics) without touching the call sites.
 *
 * # Event-name choice
 * The AC text refers to this as a "playlist-updated" SSE event. The
 * pre-existing wire constant in [SseEventNames] is `PLAYLIST_UPDATE`, and
 * the Next.js player (`web/hooks/usePlayerSse.ts`) listens with
 * `addEventListener("PLAYLIST_UPDATE", …)`. We use [SseEventNames.PLAYLIST_UPDATE]
 * here so the publisher and the player agree on a single wire name —
 * "playlist-updated" is the *conceptual* event class, `PLAYLIST_UPDATE` is
 * the literal value carried by the SSE `event:` line. Changing the wire
 * name would require a coordinated frontend roll and is out of scope for
 * this sub-AC.
 *
 * # Failure handling
 * Per-emitter `IOException`s and `IllegalStateException`s are absorbed by
 * [SseEmitterRegistry.broadcast]: failing emitters are purged from the
 * registry, the broadcast still completes for the surviving emitters, and
 * the caller never sees an exception. The publisher therefore never throws
 * for downstream-network reasons — its only failure mode is a programmer
 * error (blank deviceId), which is fail-fast on input validation.
 */
@Service
class PlaylistEventPublisher(
    private val registry: SseEmitterRegistry,
) {

    private val log = LoggerFactory.getLogger(PlaylistEventPublisher::class.java)

    /**
     * Strongly-typed entry point matching the AC contract:
     * `publishPlaylistUpdated(deviceId, payload)`.
     *
     * Builds an SSE event named [SseEventNames.PLAYLIST_UPDATE] with [payload]
     * serialised as JSON, then broadcasts it to every emitter currently
     * registered under [deviceId].
     *
     * @param deviceId target device — must be non-blank.
     * @param payload  typed payload mirroring the frontend `PlaylistUpdatePayload`.
     * @return the number of emitters that received the event successfully.
     */
    fun publishPlaylistUpdated(deviceId: String, payload: PlaylistUpdatedPayload): Int {
        require(deviceId.isNotBlank()) { "deviceId must not be blank" }
        return broadcast(deviceId, payload)
    }

    /**
     * Loose-typed overload for callers that already hold a serialisable
     * payload (e.g. a `Map<String, Any?>` assembled in a domain service that
     * does not depend on this module). Treats [payload] as opaque JSON —
     * Spring's `MappingJackson2HttpMessageConverter` will serialise it via
     * the default `ObjectMapper`.
     *
     * @param deviceId target device — must be non-blank.
     * @param payload  arbitrary JSON-serialisable object.
     * @return the number of emitters that received the event successfully.
     */
    fun publishPlaylistUpdated(deviceId: String, payload: Any): Int {
        require(deviceId.isNotBlank()) { "deviceId must not be blank" }
        return broadcast(deviceId, payload)
    }

    private fun broadcast(deviceId: String, payload: Any): Int {
        // Snapshot the live emitters first so we can short-circuit the build
        // for a device with no listeners (saves the JSON serialisation when
        // nobody is connected — relevant for the round-robin demo where a
        // playlist mutation can fire while no player is open). The registry
        // also fan-outs internally; this snapshot is purely an early-exit.
        val live = registry.getByDeviceId(deviceId)
        if (live.isEmpty()) {
            log.debug("PLAYLIST_UPDATE skipped (no live emitters): device={}", deviceId)
            return 0
        }

        val event = SseEmitter.event()
            .name(SseEventNames.PLAYLIST_UPDATE)
            .data(payload)

        val delivered = registry.broadcast(deviceId, event)
        log.info(
            "PLAYLIST_UPDATE → device={} emitters={} delivered={}",
            deviceId,
            live.size,
            delivered,
        )
        return delivered
    }
}
