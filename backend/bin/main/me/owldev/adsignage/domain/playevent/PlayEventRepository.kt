package me.owldev.adsignage.domain.playevent

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.Instant

/**
 * Spring Data JPA repository for [PlayEvent].
 *
 * Two read paths are supported beyond the inherited CRUD surface:
 *
 *  - [countByAdIdAndEventTypeAndOccurredAtBetween] — daily-cap aggregate
 *    (`COUNT(*) WHERE ad_id = ? AND event_type = 'FINISHED' AND
 *    occurred_at IN [start, end)`). Used by future admin reporting and by a
 *    sibling sub-AC that may surface "server says: N/M plays today" on the
 *    admin dashboard. The composite index `idx_play_events_ad_event_time`
 *    covers this query.
 *  - [countDistinctDevicesByAdId] — "how many unique devices have played
 *    this ad?" The same telemetry that backs cross-device cap reconciliation
 *    in the next AC.
 *
 * Spring Data derives both queries from the method names; the explicit JPQL
 * for the distinct-device count is just to make the intent unambiguous.
 */
@Repository
interface PlayEventRepository : JpaRepository<PlayEvent, String> {

    /**
     * Number of play events for [adId] of [eventType] whose `occurred_at`
     * falls inside the half-open interval `[from, to)`. Inclusive on the
     * lower bound, exclusive on the upper, matching how
     * `web/lib/playlist.ts: isAdActive` already models day-windows.
     */
    fun countByAdIdAndEventTypeAndOccurredAtBetween(
        adId: String,
        eventType: PlayEventType,
        from: Instant,
        to: Instant,
    ): Long

    /**
     * Distinct-device fan-out for an ad. Useful for the campaign report
     * ("did this ad reach 12 devices today?"); not on the hot write path.
     */
    @Query(
        "SELECT COUNT(DISTINCT pe.deviceId) FROM PlayEvent pe " +
            "WHERE pe.adId = :adId AND pe.eventType = :eventType",
    )
    fun countDistinctDevicesByAdId(
        @Param("adId") adId: String,
        @Param("eventType") eventType: PlayEventType,
    ): Long
}
