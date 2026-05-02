package me.owldev.adsignage.domain.ad

import jakarta.validation.Valid
import me.owldev.adsignage.auth.jwt.AdvertiserPrincipal
import me.owldev.adsignage.domain.ad.dto.AdResponse
import me.owldev.adsignage.domain.ad.dto.UpdateAdScheduleRequest
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.LocalTime

/**
 * REST endpoints for the [Ad] aggregate.
 *
 * Sub-AC 2 of AC 3 scope — `PUT /api/ads/{id}/schedule`:
 *  - **PUT and PATCH both honoured.** The hackathon AC names "PUT/PATCH";
 *    we register both so admin-UI clients can pick either verb without a
 *    405. Either verb sends a *complete* schedule replacement — the service
 *    overwrites all three fields — so semantically this is a PUT, but PATCH
 *    is allowed for clients that prefer the conventional partial-update
 *    verb.
 *  - **JWT-required.** Spring Security has rejected unauthenticated requests
 *    before this method runs; the [AdvertiserPrincipal] argument is therefore
 *    guaranteed non-null. The verified advertiser id is forwarded to the
 *    service, which performs the ownership-aware lookup so cross-advertiser
 *    id-guessing maps to 404.
 *  - **Validation.** Field-level constraints (`@NotNull`, `@Min`, `@Max`,
 *    `HH:mm` parse) live on [UpdateAdScheduleRequest] and are triggered by
 *    `@Valid`. The cross-field rule "endTime > startTime" is enforced in
 *    the service and surfaced as 400 with a `fieldErrors` map matching the
 *    Bean Validation shape — see the
 *    [me.owldev.adsignage.web.GlobalExceptionHandler] mapping for
 *    [InvalidScheduleException].
 *
 * HTTP contract:
 *  - 200 OK            on success (response body = [AdResponse])
 *  - 400 Bad Request   on field validation failure or invalid schedule window
 *  - 401 Unauthorized  if no/invalid JWT (handled by SecurityConfig)
 *  - 404 Not Found     if the ad id is unknown *or* not owned by the caller
 */
@RestController
@RequestMapping("/api/ads")
class AdController(
    private val adService: AdService,
) {

    private val log = LoggerFactory.getLogger(AdController::class.java)

    /**
     * Updates the schedule for ad [id]. PUT semantics — the caller sends a
     * complete schedule (start, end, daily count) and the server replaces
     * the row's schedule fields wholesale.
     */
    @PutMapping(
        "/{id}/schedule",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    fun putSchedule(
        @PathVariable("id") adId: String,
        @Valid @RequestBody body: UpdateAdScheduleRequest,
        @AuthenticationPrincipal principal: AdvertiserPrincipal,
    ): ResponseEntity<AdResponse> = updateSchedule(adId, body, principal, "PUT")

    /**
     * Sibling of [putSchedule] under the PATCH verb. Same wire contract —
     * the request body is still required to carry all three schedule
     * fields. Provided for clients that prefer PATCH for "partial-shape"
     * resource mutations.
     */
    @PatchMapping(
        "/{id}/schedule",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    fun patchSchedule(
        @PathVariable("id") adId: String,
        @Valid @RequestBody body: UpdateAdScheduleRequest,
        @AuthenticationPrincipal principal: AdvertiserPrincipal,
    ): ResponseEntity<AdResponse> = updateSchedule(adId, body, principal, "PATCH")

    // -------------------------------------------------------------------------
    // internals
    // -------------------------------------------------------------------------

    private fun updateSchedule(
        adId: String,
        body: UpdateAdScheduleRequest,
        principal: AdvertiserPrincipal,
        verb: String,
    ): ResponseEntity<AdResponse> {
        // Bean Validation has already proved these are non-null; the !! here
        // is a safe extraction so the service signature can take non-nullable
        // types and reflect the post-validation invariant.
        val startTime: LocalTime = body.startTime!!
        val endTime: LocalTime = body.endTime!!
        val dailyPlayCount: Int = body.dailyPlayCount!!

        log.info(
            "{} /api/ads/{}/schedule advertiserId={} startTime={} endTime={} dailyPlayCount={}",
            verb, adId, principal.advertiserId, startTime, endTime, dailyPlayCount,
        )

        val saved = adService.updateSchedule(
            adId = adId,
            advertiserId = principal.advertiserId,
            startTime = startTime,
            endTime = endTime,
            dailyPlayCount = dailyPlayCount,
        )
        return ResponseEntity.ok(AdResponse.from(saved))
    }
}
