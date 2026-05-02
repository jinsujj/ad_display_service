package me.owldev.adsignage.domain.playevent

import me.owldev.adsignage.domain.playevent.dto.CreatePlayEventRequest
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Service that records [PlayEvent]s for the player's "ad started / ad
 * finished" telemetry channel (`POST /api/devices/{deviceId}/play-events`)
 * and exposes the post-write daily count so callers can observe the
 * authoritative server-side increment.
 *
 * Two responsibilities, both rooted in the same canonical day-window:
 *
 *  1. **Persist** exactly one row per signal (no deduplication, no rate
 *     limiting). The server is the authoritative *log*; the player is the
 *     authoritative *cap enforcer* for latency-sensitive blocking.
 *  2. **Report** the campaign-wide FINISHED count for an ad on "today" in a
 *     consistent timezone. Sub-AC 20203 promotes this from "future read
 *     path" to a wired endpoint — both the POST response and the dashboard
 *     GET (`/api/ads/{adId}/play-events/daily-count`) compute the count
 *     through the same private helper so the increment a client observes
 *     after `POST` exactly matches what the dashboard later reads.
 *
 * **Why a single timezone (operator-local) rather than UTC.** The cap
 * `web/lib/dailyCount.ts` enforces rolls over at *device-local midnight*,
 * matching the operator's wall-clock day (`startTime`/`endTime` are
 * "HH:mm" in local time). The server must use the same calendar pivot or
 * a play recorded at 23:30 KST would land on yesterday's count from the
 * server's perspective and tomorrow's from the player's. The zone is
 * configurable via `adsignage.daily-count.zone-id` and defaults to
 * `Asia/Seoul` to match the demo deployment at stream.owl-dev.me.
 *
 * **Why FINISHED-only counts.** The daily cap is for completed
 * playthroughs — see [PlayEventType] docstring. STARTED rows still
 * persist (so reporting can surface watch-through rates) but never feed
 * the cap aggregate. The DTO field is named `serverDailyCount` rather
 * than `serverDailyFinishedCount` because every consumer of "daily
 * count" in this codebase already implicitly means FINISHED.
 *
 * Why a thin service rather than persisting straight from the controller:
 *  - Keeps the [PlayEventRepository] off the controller's surface so a
 *    future ownership / advertiser-isolation pass can land in one method.
 *  - The `occurredAt` fallback (player-supplied, else server time) belongs
 *    here, not in the controller — it is a domain rule, not a wire concern.
 *  - Mirrors the structure of every other domain in the codebase
 *    ([me.owldev.adsignage.domain.ad.AdService],
 *    [me.owldev.adsignage.domain.assignment.DeviceAssignmentService]).
 */
@Service
class PlayEventService(
    private val playEventRepository: PlayEventRepository,
    @Value("\${adsignage.daily-count.zone-id:Asia/Seoul}")
    private val zoneIdProperty: String,
) {

    private val log = LoggerFactory.getLogger(PlayEventService::class.java)

    /**
     * The zone the calendar-day pivot uses. Resolved once at construction
     * (an invalid value crashes startup with `ZoneRulesException`, which
     * is the right failure mode — a misconfigured pivot would silently
     * miscount otherwise).
     */
    private val zoneId: ZoneId = ZoneId.of(zoneIdProperty)

    /**
     * Persists a single [PlayEvent] for [deviceId] derived from [request]
     * and returns the persisted row paired with the post-increment server
     * daily count (FINISHED events for the ad's calendar day in the
     * configured zone).
     *
     * Pre-conditions:
     *  - [deviceId] is non-blank (controller path-variable; framework guard).
     *  - [request] has passed Bean Validation — `adId` is non-blank and
     *    `eventType` is a valid [PlayEventType].
     *
     * Behaviour:
     *  - `occurredAt` defaults to "now" if the player omitted it. We don't
     *    reject a missing value because a barebones client (curl from the
     *    demo desk) shouldn't have to hand-roll ISO-8601 just to ping the
     *    endpoint.
     *  - `receivedAt` is always stamped server-side via the entity default,
     *    even when the player supplied `occurredAt`, so the log can be
     *    sorted by network-arrival order regardless of device clock skew.
     *  - The save runs inside a write transaction so a future @Async
     *    listener (e.g. fan-out to an analytics topic) can hook
     *    AFTER_COMMIT semantics consistent with [AdService]. The count
     *    read inside the same transaction sees the just-inserted row, so
     *    the response always reflects the post-increment value.
     *  - The count is computed against `occurredAt` (the wall-clock the
     *    player believes the event happened at) so a STARTED reported with
     *    a player-supplied 23:59:30 timestamp is counted in the same day
     *    as the corresponding FINISHED reported a minute later — even if
     *    the FINISHED's server-receipt straddles midnight in transit.
     */
    @Transactional
    fun record(deviceId: String, request: CreatePlayEventRequest): RecordedPlayEvent {
        // Bean Validation has already proven these are non-null; the !!
        // collapses Kotlin's nullable shape into the entity's invariant.
        val adId = request.adId!!
        val eventType = request.eventType!!
        val occurredAt = request.occurredAt ?: Instant.now()

        val saved = playEventRepository.save(
            PlayEvent(
                deviceId = deviceId,
                adId = adId,
                eventType = eventType,
                occurredAt = occurredAt,
            ),
        )
        // Read the post-increment count inside the same transaction so we
        // observe the row we just wrote. Always reports FINISHED — the cap
        // semantic — regardless of which event type was recorded.
        val serverDailyCount = dailyFinishedCount(adId = adId, at = occurredAt)

        // Demo-friendly logging — operators tail this line during the live
        // run to confirm play events are landing on the server.
        log.info(
            "play-event recorded: id={} deviceId={} adId={} eventType={} occurredAt={} serverDailyCount={}",
            saved.id, saved.deviceId, saved.adId, saved.eventType, saved.occurredAt, serverDailyCount,
        )
        return RecordedPlayEvent(event = saved, serverDailyCount = serverDailyCount)
    }

    /**
     * Server-side daily FINISHED count for [adId] on the calendar day that
     * contains [at] (configured zone). Read-only; safe to call from any
     * transaction context. Use this from any read path that needs the
     * authoritative cap — the increment a client observes through
     * [record] uses the same window math.
     *
     * @param adId Ad whose plays are being counted. No FK; an unknown id
     *             returns 0 rather than 404 so the dashboard can show
     *             "0 plays today" for newly-created ads.
     * @param at   Instant whose calendar day defines the window. Defaults
     *             to "now" — pass a player-supplied `occurredAt` from
     *             [record] so the server's bookkeeping aligns with the
     *             player's belief about when the event happened.
     */
    @Transactional(readOnly = true)
    fun dailyFinishedCount(adId: String, at: Instant = Instant.now()): Long {
        val (from, to) = dailyWindow(at)
        return playEventRepository.countByAdIdAndEventTypeAndOccurredAtBetween(
            adId = adId,
            eventType = PlayEventType.FINISHED,
            from = from,
            to = to,
        )
    }

    /**
     * Half-open `[startOfDay, startOfNextDay)` window in the configured
     * zone, rendered as UTC instants for the JPA query. Centralising the
     * pivot here is the entire reason this service has the responsibility
     * — `record` and any future read endpoint MUST agree on the window or
     * a client will see "POST returned 47" followed by "GET returned 46".
     *
     * Why half-open: matches `web/lib/playlist.ts: isAdActive` (start
     * inclusive, end exclusive) and avoids the off-by-one ambiguity at
     * the midnight boundary — exactly one day owns 00:00:00.000.
     */
    private fun dailyWindow(at: Instant): Pair<Instant, Instant> {
        val day: LocalDate = at.atZone(zoneId).toLocalDate()
        val from = day.atStartOfDay(zoneId).toInstant()
        val to = day.plusDays(1).atStartOfDay(zoneId).toInstant()
        return from to to
    }
}

/**
 * Tuple returned by [PlayEventService.record]: the persisted row plus the
 * post-increment server-side daily FINISHED count for the ad. Distinct
 * type (rather than `Pair`) so the controller's call-site reads as the
 * domain concept ("recorded event with current count") rather than a
 * structural pair that could mean anything.
 *
 * `serverDailyCount` is the FINISHED count for the ad in the calendar day
 * that contains the event's `occurredAt`, computed in the configured
 * zone. See [PlayEventService] docstring for the timezone rationale.
 */
data class RecordedPlayEvent(
    val event: PlayEvent,
    val serverDailyCount: Long,
)
