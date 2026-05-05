package me.owldev.adsignage.bounded.context.device.adapter.`in`.sse

import me.owldev.adsignage.common.sse.ConnectedPayload
import me.owldev.adsignage.common.sse.SseEventNames
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
 * Sub-AC 50002.2 — 차세대 플레이어용 SSE 스트림 진입점.
 *
 *   GET /api/devices/{deviceId}/stream  →  text/event-stream
 *
 * [DeviceSseController](`/api/devices/{id}/events`) 의 형제. 두 라우트를
 * 나란히 유지하여 이미 배포된 안드로이드 WebView 가 롤오버 동안에도 계속
 * 동작하도록 한다.
 */
@RestController
@RequestMapping("/api/devices/{deviceId}/stream")
class DeviceStreamController(
    private val registry: SseEmitterRegistry,
) {

    private val log = LoggerFactory.getLogger(DeviceStreamController::class.java)

    private val sseTimeoutMillis: Long = 0L

    @GetMapping(produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun stream(@PathVariable("deviceId") deviceId: String): SseEmitter {
        require(deviceId.isNotBlank()) { "deviceId must not be blank" }

        val emitter = SseEmitter(sseTimeoutMillis)
        registry.register(deviceId, emitter)

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
