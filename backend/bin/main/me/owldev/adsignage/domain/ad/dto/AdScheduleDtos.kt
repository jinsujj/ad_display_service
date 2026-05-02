package me.owldev.adsignage.domain.ad.dto

import com.fasterxml.jackson.annotation.JsonFormat
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotNull
import me.owldev.adsignage.domain.ad.Ad
import java.time.Instant
import java.time.LocalTime

/**
 * Request body for `PUT /api/ads/{id}/schedule`.
 *
 * Carries the three schedule concepts from the ontology — start_time,
 * end_time, and daily_play_count — in a strongly-typed DTO so Bean
 * Validation can reject malformed payloads before they reach the service:
 *
 *  - `startTime` / `endTime` parse from `HH:mm` strings (configured via
 *    Jackson's [JsonFormat]). Out-of-range values like `25:00` raise
 *    `HttpMessageNotReadableException` — Jackson's parser fails before
 *    bean validation runs, which the global handler maps to 400.
 *  - `dailyPlayCount` is bounded `[1, 10000]`. The lower bound mirrors the
 *    DB CHECK constraint `ck_ads_daily_play_count_positive` (so the API
 *    matches the storage invariant); the upper bound is a sanity cap for
 *    the hackathon — at 1 second per video, 10000 plays/day is already
 *    >2.7 hours of unique playback per device, well past the demo's needs.
 *
 * Note: the cross-field rule "endTime > startTime" is **not** enforced here
 * — Bean Validation has no native multi-field constraint that maps cleanly
 * to a JSON field-error response. The service layer asserts it and raises
 * [me.owldev.adsignage.domain.ad.InvalidScheduleException] which the global
 * handler renders identically to a single-field validation failure.
 */
data class UpdateAdScheduleRequest(
    @field:NotNull(message = "startTime must not be null")
    @field:JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "HH:mm")
    val startTime: LocalTime?,

    @field:NotNull(message = "endTime must not be null")
    @field:JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "HH:mm")
    val endTime: LocalTime?,

    @field:NotNull(message = "dailyPlayCount must not be null")
    @field:Min(value = 1, message = "dailyPlayCount must be at least 1")
    @field:Max(value = 10_000, message = "dailyPlayCount must be at most 10000")
    val dailyPlayCount: Int?,
)

/**
 * Response body for `PUT /api/ads/{id}/schedule` (and any future single-ad
 * read endpoint).
 *
 * Mirrors the persisted [Ad] as a stable wire contract — keeps Hibernate-
 * managed state out of the controller boundary and lets the JSON field
 * set evolve independently of the entity column layout. Times are
 * serialized as `HH:mm` to match the request shape so a client can round-
 * trip without re-formatting.
 */
data class AdResponse(
    val id: String,
    val advertiserId: String,
    val title: String,
    val videoFilename: String,
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "HH:mm")
    val startTime: LocalTime,
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "HH:mm")
    val endTime: LocalTime,
    val dailyPlayCount: Int,
    val createdAt: Instant,
) {
    companion object {
        fun from(entity: Ad): AdResponse = AdResponse(
            id = entity.id,
            advertiserId = entity.advertiserId,
            title = entity.title,
            videoFilename = entity.videoFilename,
            startTime = entity.startTime,
            endTime = entity.endTime,
            dailyPlayCount = entity.dailyPlayCount,
            createdAt = entity.createdAt,
        )
    }
}
