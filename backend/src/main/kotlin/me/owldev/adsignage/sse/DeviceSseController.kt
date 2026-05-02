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
 * 단일 디바이스에 대한 실시간 MAPPING_CHANGED / PLAYLIST_UPDATE 푸시를 받기 위해
 * Next.js 플레이어 페이지가 구독하는 SSE 엔드포인트.
 *
 *  GET /api/devices/{id}/events  →  text/event-stream
 *
 * 와이어 계약:
 *  - 초기 이벤트:    `event: CONNECTED`  with [ConnectedPayload].
 *  - 리매핑 시:      `event: MAPPING_CHANGED` with [MappingChangedPayload].
 *  - 하트비트:       25초마다 코멘트 라인(플레이어의 EventSource 자동 재연결로
 *                    처리; 초기 이벤트를 보내 연결을 유지하고 라이브니스는
 *                    스프링의 [SseEmitter] 타임아웃에 의존).
 *
 * 타임아웃: 0L = "서버 측 타임아웃 없음" — 클라이언트가 연결을 끊을 때까지
 * 연결이 유지됨. 프록시 nginx 등은 장기 스트림용으로 설정되어야 함
 * (proxy_read_timeout 높게 또는 0; proxy_buffering off). 해당 프록시
 * 설정은 deploy/ 측의 책임이며, 리버스 프록시 연결 담당자가 짝(pair)을
 * 명확히 알 수 있도록 여기에 문서화함.
 *
 * 인증 노트: 이 라우트는 해커톤용 SecurityConfig에서 열려 있음. JWT 기반
 * 검사(광고주는 자기 디바이스에 대해서만 스트리밍 가능)는 sub-AC 5.2
 * 범위 밖 — auth-and-isolation 패스에서 처리됨.
 */
@RestController
@RequestMapping("/api/devices/{id}/events")
class DeviceSseController(
    private val registry: DeviceSseRegistry,
) {

    private val log = LoggerFactory.getLogger(DeviceSseController::class.java)

    /** SSE 스트림에 대한 서버 측 타임아웃 없음 — 플레이어는 장기 연결을
     *  유지; 소켓이 끊어지면 EventSource 클라이언트가 재연결을 처리. */
    private val sseTimeoutMillis: Long = 0L

    @GetMapping(produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun stream(@PathVariable("id") deviceId: String): SseEmitter {
        require(deviceId.isNotBlank()) { "deviceId must not be blank" }

        val emitter = SseEmitter(sseTimeoutMillis)
        registry.register(deviceId, emitter)

        // 클라이언트가 채널이 열렸음을 알 수 있도록 즉시 핸드셰이크 이벤트를
        // 전송 — registry/네트워크가 끊어진 경우 (유휴 파이프 대신 5xx로)
        // 서버 측 오류를 빠르게 노출하는 효과도 있음.
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
