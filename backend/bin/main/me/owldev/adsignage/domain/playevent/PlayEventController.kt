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
 * AC 20202 Sub-AC 2 — REST endpoint that receives play events from the
 * Next.js player so server-side counts can be updated.
 *
 * Wire contract:
 *
 *   POST /api/devices/{deviceId}/play-events
 *   Content-Type: application/json
 *
 *   {
 *     "adId":       "<uuid>",
 *     "eventType":  "STARTED" | "FINISHED",
 *     "occurredAt": "2026-05-02T11:21:13Z"     // optional
 *   }
 *
 *   ⇒ 201 Created
 *      { id, deviceId, adId, eventType, occurredAt, receivedAt }
 *   ⇒ 400 Bad Request on validation failure (missing adId / unknown type)
 *
 * Why a public (no-JWT) endpoint:
 *  - The player runs on an Android WebView that has no advertiser identity
 *    — the device is anonymous, and the `deviceId` path parameter is the
 *    bearer of identity for the hackathon (see SecurityConfig). The sibling
 *    SSE / playlist routes (`/api/devices/{id}/stream`,
 *    `/api/devices/{id}/playlist`) use the same convention and are likewise
 *    allow-listed.
 *  - Auth-and-isolation pass (a later AC) will tighten this with a device
 *    enrolment token; until then the endpoint is rate-limit-free and
 *    accepts any payload that conforms to the DTO.
 *
 * Why a per-device path (rather than `/api/play-events`):
 *  - Keeps the URL hierarchy aligned with the other player-side routes
 *    (`stream`, `playlist`, `assignment`, `restaurant`) — every device-
 *    facing path is rooted at `/api/devices/{deviceId}/…`. Operators can
 *    reason about a single device's traffic by grepping its UUID through
 *    the access logs.
 *  - Spring's `SecurityConfig` already uses single-segment `*` matchers
 *    (`/api/devices/{id}/stream`) so adding `/api/devices/{id}/play-events`
 *    fits the existing allow-list pattern with no surprises.
 *
 * Status code rationale:
 *  - 201 Created mirrors `POST /api/devices/{id}/assignment` — every POST
 *    in this codebase that materialises a row returns 201 with the
 *    persisted entity, never 200.
 */
@RestController
@RequestMapping("/api/devices/{deviceId}/play-events")
class PlayEventController(
    private val playEventService: PlayEventService,
) {

    private val log = LoggerFactory.getLogger(PlayEventController::class.java)

    /**
     * Records a single play event for the device whose path id is
     * [deviceId]. Returns 201 with the persisted [PlayEventResponse].
     *
     * Field-level validation is enforced by `@Valid` against
     * [CreatePlayEventRequest]; cross-field rules (none today) would be
     * mapped through `GlobalExceptionHandler` if/when they appear. An
     * unknown enum value on `eventType` produces a Jackson
     * `HttpMessageNotReadableException` → 400, which already lands
     * uniformly via the existing handler chain.
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
        // AC 20203 Sub-AC 3: the service returns the persisted row paired
        // with the post-increment server daily count so the response can
        // surface the cap delta atomically (no follow-up GET round-trip).
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
