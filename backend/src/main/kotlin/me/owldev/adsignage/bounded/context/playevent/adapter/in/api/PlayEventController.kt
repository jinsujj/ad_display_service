package me.owldev.adsignage.bounded.context.playevent.adapter.`in`.api

import jakarta.validation.Valid
import me.owldev.adsignage.bounded.context.playevent.application.service.PlayEventService
import me.owldev.adsignage.bounded.context.playevent.domain.dto.CreatePlayEventRequest
import me.owldev.adsignage.bounded.context.playevent.domain.dto.PlayEventResponse
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
 *      { id, deviceId, adId, eventType, occurredAt, receivedAt, serverDailyCount }
 *   ⇒ 400 Bad Request 검증 실패 시 (adId 누락 / 알 수 없는 타입)
 *
 * 왜 공개(JWT 없음) 엔드포인트인가:
 *  - 플레이어는 광고주 신원이 없는 Android WebView에서 실행됨 — 디바이스는
 *    익명이고, `deviceId` 경로 파라미터가 해커톤에서 신원의 운반자임
 *    (SecurityConfig 참조). 형제 SSE / 플레이리스트 라우트도 동일한 관례를
 *    사용하며 마찬가지로 허용 목록에 등록됨.
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
