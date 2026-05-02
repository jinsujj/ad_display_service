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
 * Sub-AC 50002.2 — 플레이어 페이지를 위한 SSE 스트림 진입점.
 *
 *   GET /api/devices/{deviceId}/stream  →  text/event-stream
 *
 * # 책임
 *  - "무한" 서버 측 타임아웃을 갖는 새 [SseEmitter]를 생성하여 장기 플레이어
 *    연결이 스프링의 기본 30초 윈도우에 의해 끊기지 않도록 함. 프록시
 *    nginx는 일치하는 long-poll 동작(`proxy_buffering off`, 높은
 *    `proxy_read_timeout`)을 위해 별도(deploy/)에서 설정됨.
 *  - emitter를 `deviceId`로 키잉하여 [SseEmitterRegistry]에 등록하므로,
 *    다운스트림 브로드캐스터(예: [DeviceMappingChangedSseListener])가 해당
 *    디바이스의 모든 라이브 연결로 이벤트를 팬아웃 가능.
 *  - 즉시 `CONNECTED` 핸드셰이크 이벤트를 전송하여 클라이언트가 파이프가
 *    정상임을 알 수 있도록 함(emitter 셋업 중 5xx가 발생하면 유휴 정체가
 *    아닌 즉각 노출되도록 함).
 *  - @GetMapping의 `produces=`에 [MediaType.TEXT_EVENT_STREAM_VALUE]를
 *    선언하여 상위 프록시가 스프링의 기본 Accept 추론을 제거하더라도 SSE
 *    와이어 포맷이 협상되도록 함.
 *
 * # 왜 [DeviceSseController]와 별도 컨트롤러인가
 * [DeviceSseController]는 이미 출시된 AC 5 SSE 와이어를 위해
 * `/api/devices/{id}/events`에 마운트됨. AC 50002에서 새 계약 경로 —
 * `/api/devices/{deviceId}/stream` — 가 도입되었고, 이는 차세대 플레이어가
 * 구독하는 경로. 라우팅을 변경하지 않고 두 경로를 나란히 유지하여
 * /events 호출자(이미 배포된 안드로이드 WebView)가 롤오버 동안에도 계속
 * 동작하도록 하고, AC 5의 와이어 계약을 여기서 다시 테스트하지 않도록 함.
 *
 * # 인증
 * 이 엔드포인트는 인증되지 않은 디바이스에 노출됨 — deviceId 경로 변수가
 * 해커톤에서 신원의 운반자(bearer)임. SecurityConfig는 기존 `/events`
 * 예외와 함께 새 `/stream` 경로를 허용 목록에 등록함.
 */
@RestController
@RequestMapping("/api/devices/{deviceId}/stream")
class DeviceStreamController(
    private val registry: SseEmitterRegistry,
) {

    private val log = LoggerFactory.getLogger(DeviceStreamController::class.java)

    /**
     * 0L = "서버 측 타임아웃 없음" — 클라이언트가 연결을 끊을 때까지 연결이
     * 유지됨. 클라이언트(브라우저 EventSource)는 기저 소켓이 닫히면 스스로
     * 재연결을 주도하므로, 서버는 고정 주기로 emitter를 재활용할 필요가 없음.
     */
    private val sseTimeoutMillis: Long = 0L

    @GetMapping(produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun stream(@PathVariable("deviceId") deviceId: String): SseEmitter {
        require(deviceId.isNotBlank()) { "deviceId must not be blank" }

        val emitter = SseEmitter(sseTimeoutMillis)
        registry.register(deviceId, emitter)

        // 즉시 핸드셰이크 — 클라이언트에게 파이프가 살아있음을 확인시키고
        // 와이어 수준 오류를 빠르게 노출(조용한 유휴 파이프 대신 5xx).
        // reconnectTime()은 EventSource에 연결 끊김 시 브라우저 기본 3초가
        // 아닌 2초의 백오프를 힌트로 줌.
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
