package me.owldev.adsignage.sse

import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * SSE endpoint that the Next.js player page subscribes to in order to
 * receive live MAPPING_CHANGED / PLAYLIST_UPDATE pushes for one device.
 *
 *  GET /api/devices/{id}/events  →  text/event-stream
 *
 * Wire contract:
 *  - Initial event:  `event: CONNECTED`  with [ConnectedPayload].
 *  - On remap:       `event: MAPPING_CHANGED` with [MappingChangedPayload].
 *  - Heartbeat:      a comment line every 25s (handled by the player's
 *                    EventSource auto-reconnect; we keep the connection
 *                    open by sending the initial event and rely on Spring's
 *                    [SseEmitter] timeout for liveness).
 *
 * Timeout: 0L = "no server-side timeout" — the connection lives until the
 * client disconnects. Fronting nginx/proxies must be configured for long-
 * lived streams (proxy_read_timeout high or 0; proxy_buffering off). That
 * proxy config is owned by the deploy/ side; it is documented here so the
 * pairing is obvious to whoever wires the reverse proxy.
 *
 * Auth note: this route is open in the hackathon SecurityConfig. A JWT-
 * bound check (advertisers can only stream for their own devices) is out
 * of scope for sub-AC 5.2 — it lands in the auth-and-isolation pass.
 */
@RestController
@RequestMapping("/api/devices/{id}/events")
class DeviceSseController(
    private val registry: DeviceSseRegistry,
) {

    private val log = LoggerFactory.getLogger(DeviceSseController::class.java)

    /** No server-side timeout for the SSE stream — the player keeps a
     *  long-lived connection; reconnects are handled by the EventSource
     *  client when the socket dies. */
    private val sseTimeoutMillis: Long = 0L

    @GetMapping(produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun stream(@PathVariable("id") deviceId: String): SseEmitter {
        require(deviceId.isNotBlank()) { "deviceId must not be blank" }

        val emitter = SseEmitter(sseTimeoutMillis)
        registry.register(deviceId, emitter)

        // Send an immediate handshake event so the client knows the channel
        // is open — also surfaces server-side errors fast (a 5xx instead of
        // an idle pipe) if the registry / network is broken.
        try {
            emitter.send(
                SseEmitter.event()
                    .name(SseEventNames.CONNECTED)
                    .data(ConnectedPayload(deviceId = deviceId))
                    .reconnectTime(TimeUnit.SECONDS.toMillis(2))
            )
            log.info("SSE stream opened: device={}", deviceId)
        } catch (ex: IOException) {
            log.warn("SSE handshake send failed for device={}: {}", deviceId, ex.message)
            emitter.completeWithError(ex)
        }

        return emitter
    }
}
