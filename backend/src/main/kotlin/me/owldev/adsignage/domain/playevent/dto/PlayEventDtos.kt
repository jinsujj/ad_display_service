package me.owldev.adsignage.domain.playevent.dto

import com.fasterxml.jackson.annotation.JsonFormat
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import me.owldev.adsignage.domain.playevent.PlayEvent
import me.owldev.adsignage.domain.playevent.PlayEventType
import java.time.Instant

/**
 * Request body for `POST /api/devices/{deviceId}/play-events`.
 *
 * Wire shape:
 * ```json
 * {
 *   "adId":       "<uuid>",
 *   "eventType":  "STARTED" | "FINISHED",
 *   "occurredAt": "2026-05-02T11:21:13.000Z"   // optional
 * }
 * ```
 *
 * Validation choices:
 *  - `adId` — non-blank UUID. We don't FK-validate against the `ads` table
 *    because telemetry must outlive an ad deletion (see
 *    [me.owldev.adsignage.domain.playevent.PlayEvent] docstring).
 *  - `eventType` — Jackson auto-coerces the JSON string to
 *    [PlayEventType]; the [NotNull] guard catches a missing field with the
 *    same field-error map shape the rest of the API uses.
 *  - `occurredAt` — **optional**. The player includes it for accuracy
 *    (clock-skew analysis), but the server falls back to [Instant.now] so a
 *    minimal client never has to learn ISO-8601 just to report a play.
 */
data class CreatePlayEventRequest(
    @field:NotBlank(message = "adId must not be blank")
    val adId: String?,

    @field:NotNull(message = "eventType must not be null")
    val eventType: PlayEventType?,

    /**
     * ISO-8601 instant the event occurred at on the client. May be null —
     * the controller stamps `Instant.now()` in that case.
     */
    @field:JsonFormat(shape = JsonFormat.Shape.STRING)
    val occurredAt: Instant? = null,
)

/**
 * Response body for `POST /api/devices/{deviceId}/play-events`.
 *
 * Echoes the persisted event so the player can reconcile (e.g. log the
 * server-stamped `receivedAt` for skew detection) and so an admin probing
 * the wire by hand can see the row that was created without a follow-up
 * GET.
 *
 * AC 20203 Sub-AC 3 widened this response with [serverDailyCount] — the
 * FINISHED count for the ad in the calendar day that contains
 * [occurredAt], computed in the operator-local zone configured by
 * `adsignage.daily-count.zone-id`. Returning it inline gives the player
 * an authoritative cross-device count it can compare against its
 * localStorage tally, and saves the dashboard a follow-up read after a
 * recently-recorded event. The earlier "intentionally omitted" note has
 * been superseded — the read happens inside the write transaction so it
 * is at most one extra COUNT(*) against the (`ad_id`, `event_type`,
 * `occurred_at`) covering index, well within the hot-path budget.
 */
data class PlayEventResponse(
    val id: String,
    val deviceId: String,
    val adId: String,
    val eventType: PlayEventType,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val occurredAt: Instant,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val receivedAt: Instant,
    /**
     * Number of FINISHED events the server has recorded for [adId] on the
     * calendar day containing [occurredAt] (operator-local zone). Always a
     * non-negative `Long`; for STARTED events this is the count *as of the
     * STARTED's occurredAt*, so two STARTEDs in a row will see the same
     * value and a STARTED followed by a FINISHED will see N then N+1.
     *
     * Always present on the response — never omitted. The player uses it
     * for cross-device reconciliation; the admin dashboard surfaces it as
     * "N plays today (server)".
     */
    val serverDailyCount: Long,
) {
    companion object {
        /**
         * Compose the response from a persisted entity plus the server's
         * authoritative daily count. The count is supplied by the caller
         * (rather than computed here) because the count read must happen
         * inside the same transaction as the write — see
         * [me.owldev.adsignage.domain.playevent.PlayEventService.record].
         */
        fun from(entity: PlayEvent, serverDailyCount: Long): PlayEventResponse = PlayEventResponse(
            id = entity.id,
            deviceId = entity.deviceId,
            adId = entity.adId,
            eventType = entity.eventType,
            occurredAt = entity.occurredAt,
            receivedAt = entity.receivedAt,
            serverDailyCount = serverDailyCount,
        )
    }
}

/**
 * Response body for `GET /api/ads/{adId}/play-events/daily-count`.
 *
 * Read-only sibling of [PlayEventResponse.serverDailyCount] for the
 * dashboard's "show me the campaign's plays today" view. Includes the
 * resolved `date` and `zoneId` so a client can detect a config drift
 * (server says 2026-05-02 but the client is rendering 2026-05-01) before
 * the operator reads "47/200" and wonders why the player widget says
 * "0/200".
 */
data class DailyPlayCountResponse(
    val adId: String,
    /** ISO-8601 calendar day, in [zoneId]. Format: `YYYY-MM-DD`. */
    val date: String,
    /** IANA zone the [date] is anchored to (e.g. `Asia/Seoul`). */
    val zoneId: String,
    /** FINISHED-event count for [adId] on [date]. Non-negative. */
    val count: Long,
)
