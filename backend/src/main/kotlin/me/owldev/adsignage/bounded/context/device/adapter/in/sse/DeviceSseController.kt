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
 * 단일 디바이스에 대한 실시간 MAPPING_CHANGED / PLAYLIST_UPDATE 푸시를 받기 위해
 * Next.js 플레이어 페이지가 구독하는 SSE 엔드포인트.
 *
 *   GET /api/devices/{id}/events  →  text/event-stream
 *
 * 와이어 계약:
 *  - 초기 이벤트:    `event: CONNECTED`
 *  - 리매핑 시:      `event: MAPPING_CHANGED`
 *  - 하트비트:       10초마다 코멘트 라인
 */
@RestController
@RequestMapping("/api/devices/{id}/events")
class DeviceSseController(
    private val registry: DeviceSseRegistry,
) {

    private val log = LoggerFactory.getLogger(DeviceSseController::class.java)

    private val sseTimeoutMillis: Long = 0L

    @GetMapping(produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun stream(@PathVariable("id") deviceId: String): SseEmitter {
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
