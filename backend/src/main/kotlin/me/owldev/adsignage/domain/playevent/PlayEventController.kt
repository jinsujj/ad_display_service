package me.owldev.adsignage.domain.playevent

import jakarta.validation.Valid
import me.owldev.adsignage.domain.playevent.dto.CreatePlayEventRequest
import me.owldev.adsignage.domain.playevent.dto.PlayEventResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * AC 20202 Sub-AC 2 — Next.js 플레이어로부터 재생 이벤트를 받아 서버 측
 * 카운트가 업데이트될 수 있도록 하는 REST 엔드포인트.
 *
 * 와이어 계약:
 *
 *   POST /api/devices/{deviceId}/play-events
 *   Content-Type: application/json
 *
 *   {
 *     "adId":       "<uuid>",
 *     "eventType":  "STARTED" | "FINISHED",
 *     "occurredAt": "2026-05-02T11:21:13Z"     // 선택
 *   }
 *
 *   ⇒ 201 Created
 *      { id, deviceId, adId, eventType, occurredAt, receivedAt }
 *   ⇒ 400 Bad Request 검증 실패 시 (adId 누락 / 알 수 없는 타입)
 *
 * 왜 공개(JWT 없음) 엔드포인트인가:
 *  - 플레이어는 광고주 신원이 없는 Android WebView에서 실행됨 — 디바이스는
 *    익명이고, `deviceId` 경로 파라미터가 해커톤에서 신원의 운반자임
 *    (SecurityConfig 참조). 형제 SSE / 플레이리스트 라우트
 *    (`/api/devices/{id}/stream`, `/api/devices/{id}/playlist`)도 동일한
 *    관례를 사용하며 마찬가지로 허용 목록에 등록됨.
 *  - Auth-and-isolation 패스(추후 AC)가 디바이스 등록 토큰으로 이를
 *    조일 것; 그때까지 엔드포인트는 레이트 리미팅이 없으며 DTO에 부합하는
 *    어떤 페이로드든 받음.
 *
 * 왜 (`/api/play-events`가 아닌) 디바이스별 경로인가:
 *  - URL 계층을 다른 플레이어 측 라우트(`stream`, `playlist`, `assignment`,
 *    `restaurant`)와 정렬 — 모든 디바이스 대상 경로는
 *    `/api/devices/{deviceId}/…`에 뿌리를 둠. 운영자는 액세스 로그에서
 *    UUID를 grep하여 단일 디바이스의 트래픽을 추론할 수 있음.
 *  - 스프링의 `SecurityConfig`는 이미 단일 세그먼트 `*` 매처
 *    (`/api/devices/{id}/stream`)를 사용하므로 `/api/devices/{id}/play-events`
 *    를 추가하면 기존 허용 목록 패턴에 무리 없이 들어맞음.
 *
 * 상태 코드 근거:
 *  - 201 Created는 `POST /api/devices/{id}/assignment`를 미러링 — 이
 *    코드베이스에서 행을 구체화하는 모든 POST는 영속화된 엔터티와 함께
 *    201을 반환하지 절대 200이 아님.
 */
@RestController
@RequestMapping("/api/devices/{deviceId}/play-events")
class PlayEventController(
    private val playEventService: PlayEventService,
) {

    private val log = LoggerFactory.getLogger(PlayEventController::class.java)

    /**
     * 경로 id가 [deviceId]인 디바이스에 대한 단일 재생 이벤트를 기록.
     * 영속화된 [PlayEventResponse]와 함께 201을 반환.
     *
     * 필드 수준 검증은 [CreatePlayEventRequest]에 대한 `@Valid`로 강제됨;
     * 크로스 필드 규칙(현재는 없음)은 나타날 경우 `GlobalExceptionHandler`를
     * 통해 매핑됨. `eventType`의 알려지지 않은 enum 값은 Jackson의
     * `HttpMessageNotReadableException` → 400을 생성하며, 기존 핸들러 체인을
     * 통해 이미 균일하게 처리됨.
     */
    @PostMapping(
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    fun create(
        @PathVariable("deviceId") deviceId: String,
        @Valid @RequestBody body: CreatePlayEventRequest,
    ): ResponseEntity<PlayEventResponse> {
        log.info(
            "POST /api/devices/{}/play-events adId={} eventType={} clientOccurredAt={}",
            deviceId, body.adId, body.eventType, body.occurredAt,
        )
        // AC 20203 Sub-AC 3: 서비스는 영속화된 행과 함께 증가 후 서버 일일
        // 카운트를 반환하므로 응답이 cap 델타를 원자적으로 표면화할 수 있음
        // (후속 GET 왕복 없음).
        val recorded = playEventService.record(deviceId, body)
        return ResponseEntity
            .status(HttpStatus.CREATED)
            .body(
                PlayEventResponse.from(
                    entity = recorded.event,
                    serverDailyCount = recorded.serverDailyCount,
                ),
            )
    }
}
