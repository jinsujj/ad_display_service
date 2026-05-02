package me.owldev.adsignage.domain.ad

import me.owldev.adsignage.sse.PlaylistUpdatedEvent
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalTime

/**
 * Service that owns the [Ad] aggregate's mutating lifecycle.
 *
 * Sub-AC 2 of AC 3 scope:
 *  - [updateSchedule] — replaces the schedule fields (startTime, endTime,
 *    dailyPlayCount) on an ad belonging to the calling advertiser.
 *  - [findOwned]      — read helper used by the controller for non-mutating
 *    GETs (currently exposed for tests / future endpoints).
 *
 * Auth-and-isolation contract:
 *  - Every entry point takes the *verified* `advertiserId` from the JWT
 *    principal. Lookups go through
 *    [AdRepository.findByIdAndAdvertiserId], so an advertiser can only
 *    affect their own ads — cross-id access collapses to
 *    [AdNotFoundException] (HTTP 404), never leaking ad existence.
 *
 * Cross-field validation:
 *  - The DB's `ck_ads_time_window` CHECK already rejects `endTime <= startTime`,
 *    but a constraint-violation exception bubbles up as a `DataIntegrityViolation`
 *    with a vendor-specific message. We assert the rule explicitly here so
 *    the API returns a clean field-error map (`{endTime: "must be after startTime"}`)
 *    matching the shape produced by Bean Validation on field-level rules. The
 *    DB constraint stays as the last-resort guard against direct writes
 *    (seed scripts, manual SQL).
 */
@Service
class AdService(
    private val adRepository: AdRepository,
    /**
     * Sub-AC 50201.1 — schedule mutations announce themselves on the Spring
     * application event bus so the SSE bridge can fan a `PLAYLIST_UPDATE`
     * event out to every player whose playlist could be affected.
     *
     * The event is consumed by
     * [me.owldev.adsignage.sse.PlaylistUpdatedSseListener], which is wired
     * with `@TransactionalEventListener(AFTER_COMMIT)` so the broadcast
     * runs **after** the `@Transactional` boundary in [updateSchedule]
     * commits — guaranteeing that a player's refetch on receiving the
     * event will see the new schedule.
     *
     * Keeping the publisher behind the [ApplicationEventPublisher] interface
     * (rather than calling the SSE registry directly) preserves AdService's
     * existing zero-dependency posture against the SSE module — the unit
     * test boots `AdService(adRepository = mock, eventPublisher = noop)`
     * without dragging in the web/SSE layer.
     */
    private val eventPublisher: ApplicationEventPublisher,
) {

    private val log = LoggerFactory.getLogger(AdService::class.java)

    /**
     * Replaces the schedule fields on the ad whose id is [adId] **and** whose
     * owner is [advertiserId]. Returns the persisted [Ad] (with the updated
     * schedule). Runs in a single transaction so the row never observably
     * sits with a half-applied schedule.
     *
     * @throws AdNotFoundException     if no row matches `(id, advertiserId)`
     * @throws InvalidScheduleException if `endTime <= startTime`
     */
    @Transactional
    fun updateSchedule(
        adId: String,
        advertiserId: String,
        startTime: LocalTime,
        endTime: LocalTime,
        dailyPlayCount: Int,
    ): Ad {
        validateScheduleWindow(startTime, endTime)

        val ad = adRepository.findByIdAndAdvertiserId(adId, advertiserId)
            .orElseThrow {
                log.info(
                    "updateSchedule: ad not found or not owned (adId={}, advertiserId={})",
                    adId, advertiserId,
                )
                AdNotFoundException(adId)
            }

        ad.startTime = startTime
        ad.endTime = endTime
        ad.dailyPlayCount = dailyPlayCount

        // Hibernate is dirty-checking inside the @Transactional boundary, but
        // we call save() explicitly so the returned reference is the one the
        // persistence context committed (and the test can re-read it without
        // racing the flush).
        val saved = adRepository.save(ad)
        log.info(
            "updateSchedule: adId={} advertiserId={} startTime={} endTime={} dailyPlayCount={}",
            saved.id, saved.advertiserId, saved.startTime, saved.endTime, saved.dailyPlayCount,
        )
        // Sub-AC 50201.1 — announce the playlist mutation on the application
        // event bus. The matching listener is bound to AFTER_COMMIT, so the
        // SSE broadcast runs only once this `@Transactional` method commits.
        // We wrap in try/catch as a belt-and-braces guard: the publish call
        // itself is in-process and synchronous, but an unanticipated
        // pre-commit listener registered by a future feature must never be
        // able to roll back the schedule write that the caller already
        // succeeded at. AFTER_COMMIT listeners cannot fail this call.
        publishPlaylistUpdated(saved)
        return saved
    }

    /**
     * Fetches the ad whose id is [adId] **and** whose owner is [advertiserId],
     * or throws [AdNotFoundException] if either condition fails. Exposed for
     * future single-ad read endpoints (and used by tests).
     */
    @Transactional(readOnly = true)
    fun findOwned(adId: String, advertiserId: String): Ad =
        adRepository.findByIdAndAdvertiserId(adId, advertiserId)
            .orElseThrow { AdNotFoundException(adId) }

    // -------------------------------------------------------------------------
    // internals
    // -------------------------------------------------------------------------

    private fun validateScheduleWindow(startTime: LocalTime, endTime: LocalTime) {
        if (!endTime.isAfter(startTime)) {
            throw InvalidScheduleException(
                fieldErrors = mapOf(
                    "endTime" to "endTime must be strictly after startTime",
                ),
            )
        }
    }

    /**
     * Publishes the [PlaylistUpdatedEvent] consumed by
     * [me.owldev.adsignage.sse.PlaylistUpdatedSseListener]. The SSE listener
     * is bound to `TransactionPhase.AFTER_COMMIT`, so the broadcast does
     * not run until this service's enclosing transaction commits.
     *
     * The publish call is wrapped in try/catch so the schedule write is
     * never rolled back by a misbehaving event listener — failure here is
     * logged and swallowed because the caller's response (200 with the
     * persisted [Ad]) is correct regardless of whether SSE delivery
     * succeeds. Players that miss the push will pick up the new schedule
     * on their next periodic poll / reconnect.
     */
    private fun publishPlaylistUpdated(saved: Ad) {
        try {
            eventPublisher.publishEvent(
                PlaylistUpdatedEvent(
                    advertiserId = saved.advertiserId,
                    adId = saved.id,
                ),
            )
        } catch (ex: Exception) {
            log.warn(
                "Failed to publish PlaylistUpdatedEvent for adId={} advertiserId={}: {}",
                saved.id, saved.advertiserId, ex.message,
            )
        }
    }
}
