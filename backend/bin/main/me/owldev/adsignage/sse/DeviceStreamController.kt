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
 * Sub-AC 50002.2 — SSE stream entry point for the player page.
 *
 *   GET /api/devices/{deviceId}/stream  →  text/event-stream
 *
 * # Responsibilities
 *  - Construct a fresh [SseEmitter] with an "infinite" server-side timeout
 *    so the long-lived player connection is not torn down by Spring's
 *    default 30s window. The fronting nginx proxy is configured separately
 *    (deploy/) for matching long-poll behaviour (`proxy_buffering off`,
 *    high `proxy_read_timeout`).
 *  - Register the emitter with [SseEmitterRegistry] keyed by `deviceId` so
 *    downstream broadcasters (e.g. [DeviceMappingChangedSseListener]) can
 *    fan out events to every live connection for that device.
 *  - Send an immediate `CONNECTED` handshake event so the client knows the
 *    pipe is healthy (and so any 5xx during emitter setup is surfaced
 *    promptly instead of as an idle stall).
 *  - Declare the response content-type via [MediaType.TEXT_EVENT_STREAM_VALUE]
 *    on the @GetMapping `produces=` so the SSE wire format is negotiated
 *    even when an upstream proxy strips Spring's default Accept inference.
 *
 * # Why a separate controller from [DeviceSseController]
 * [DeviceSseController] mounts at `/api/devices/{id}/events` for the AC 5
 * SSE wire that already shipped. AC 50002 introduced a new contract path —
 * `/api/devices/{deviceId}/stream` — which is what the next-revision player
 * subscribes to. We keep the two side-by-side rather than rerouting so the
 * /events callers (Android WebView already deployed) keep working through
 * the rollover and we do not re-test AC 5's wire contract here.
 *
 * # Auth
 * The endpoint is exposed to unauthenticated devices — the deviceId path
 * variable is the bearer of identity for the hackathon. SecurityConfig
 * allow-lists the new `/stream` path alongside the existing `/events`
 * carve-out.
 */
@RestController
@RequestMapping("/api/devices/{deviceId}/stream")
class DeviceStreamController(
    private val registry: SseEmitterRegistry,
) {

    private val log = LoggerFactory.getLogger(DeviceStreamController::class.java)

    /**
     * 0L = "no server-side timeout" — the connection lives until the client
     * disconnects. The client (browser EventSource) drives reconnection on
     * its own when the underlying socket closes; the server does not need
     * to recycle the emitter on a fixed cadence.
     */
    private val sseTimeoutMillis: Long = 0L

    @GetMapping(produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun stream(@PathVariable("deviceId") deviceId: String): SseEmitter {
        require(deviceId.isNotBlank()) { "deviceId must not be blank" }

        val emitter = SseEmitter(sseTimeoutMillis)
        registry.register(deviceId, emitter)

        // Immediate handshake — confirms to the client that the pipe is up
        // and surfaces any wire-level error fast (5xx instead of a silent
        // idle pipe). reconnectTime() hints the EventSource to back off
        // 2s on disconnect rather than the browser default 3s.
        try {
            emitter.send(
                SseEmitter.event()
                    .name(SseEventNames.CONNECTED)
                    .data(ConnectedPayload(deviceId = deviceId))
                    .reconnectTime(TimeUnit.SECONDS.toMillis(2)),
            )
            log.info("SSE stream opened: device={}", deviceId)
        } catch (ex: IOException) {
            log.warn("SSE handshake send failed for device={}: {}", deviceId, ex.message)
            emitter.completeWithError(ex)
        }

        return emitter
    }
}
