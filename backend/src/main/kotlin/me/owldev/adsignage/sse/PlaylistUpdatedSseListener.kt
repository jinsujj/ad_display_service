package me.owldev.adsignage.sse

import me.owldev.adsignage.domain.assignment.DeviceAssignmentRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

/**
 * Bridges [PlaylistUpdatedEvent]s into the SSE wire by broadcasting a
 * [SseEventNames.PLAYLIST_UPDATE] event to every emitter currently connected
 * for a device whose playlist could be affected by the change.
 *
 * This is the listener half of the publisher/listener pair that powers the
 * "advertiser changed an ad's schedule → every active player refetches"
 * loop (sibling of the device-remap path; demo scenarios #1 + #2):
 *
 *  1. Advertiser calls `PUT/PATCH /api/ads/{id}/schedule`.
 *  2. [me.owldev.adsignage.domain.ad.AdService.updateSchedule] writes the
 *     new schedule and publishes a [PlaylistUpdatedEvent] from inside its
 *     `@Transactional` method.
 *  3. The transaction commits — only **after** the row is durable does
 *     Spring invoke this listener (it is wired with
 *     [TransactionalEventListener] at phase [TransactionPhase.AFTER_COMMIT]).
 *  4. This listener resolves the set of affected device_ids via
 *     [DeviceAssignmentRepository.findAllByActiveTrue] and asks
 *     [PlaylistEventPublisher] to push a `PLAYLIST_UPDATE` event to each.
 *  5. The Next.js player at `/player/{deviceId}`, which has a long-lived
 *     SSE subscription, receives the event and refetches its playlist.
 *
 * # Affected-device resolution
 * In the current data model an [me.owldev.adsignage.domain.ad.Ad] is global
 * across restaurants — every device's playlist potentially contains every
 * ad. The affected device set is therefore "every device that currently has
 * an active assignment". We use the active-assignment set as a soft proxy
 * for "subscribed devices" so we never push to devices that are not
 * provisioned anywhere; that keeps the broadcast quiet on bring-up and
 * matches the demo where 2 devices are wired into 2 restaurants.
 *
 * If the registry happens to hold an emitter for a device that is not in
 * the active-assignments table (e.g. a player page opened with a freshly
 * generated deviceId before the admin maps it), it simply won't receive
 * this broadcast. That is acceptable: an unmapped player has no playlist
 * to refresh, and once it gets mapped it will see the
 * [SseEventNames.MAPPING_CHANGED] event from the sibling path and fetch a
 * fresh playlist that already reflects the new schedule.
 *
 * # Why AFTER_COMMIT and not synchronous `@EventListener`
 *  - **Consistency with the DB**: if the AdService transaction rolls back
 *    (e.g. cross-field validation surfaces at flush time), no
 *    PLAYLIST_UPDATE event is sent, so players never see a phantom refresh
 *    that does not match persisted state.
 *  - **Refetch ordering**: when a player receives PLAYLIST_UPDATE and
 *    immediately fetches the playlist, the schedule row is already
 *    committed and visible to the read.
 *  - **Failure isolation**: per-emitter SSE send failures cannot roll back
 *    the schedule write because the listener fires post-commit. Within the
 *    listener itself, [PlaylistEventPublisher] delegates failure handling
 *    to [SseEmitterRegistry.broadcast], which purges bad emitters per-call
 *    without poisoning healthy siblings.
 *
 * # `fallbackExecution = true`
 * If the event is published *outside* of a transaction (e.g. a unit test
 * that calls the service without `@Transactional`, or a future ad-hoc
 * admin tool that bypasses the transactional service), Spring would
 * normally drop the event silently because there is no transaction phase
 * to bind to. Setting `fallbackExecution = true` makes Spring invoke the
 * listener immediately in that case so the broadcast still happens — same
 * choice as [DeviceMappingChangedSseListener].
 *
 * # Why we don't push to the registry's full keyset
 * The [SseEmitterRegistry] knows about every connected emitter, but we
 * deliberately don't iterate it directly. The active-assignments table is
 * the source of truth for "this device is provisioned"; iterating the
 * registry would push to disconnected/abandoned tabs and would couple this
 * listener to the registry's internal data structures. Going through the
 * publisher (which calls `registry.broadcast(deviceId, event)`) keeps the
 * registry's keyset encapsulated and lets us delete unused per-device
 * lists eagerly without touching this code.
 */
@Component
class PlaylistUpdatedSseListener(
    private val publisher: PlaylistEventPublisher,
    private val assignmentRepository: DeviceAssignmentRepository,
) {

    private val log = LoggerFactory.getLogger(PlaylistUpdatedSseListener::class.java)

    @TransactionalEventListener(
        phase = TransactionPhase.AFTER_COMMIT,
        fallbackExecution = true,
    )
    fun onPlaylistUpdated(event: PlaylistUpdatedEvent) {
        val activeAssignments = try {
            assignmentRepository.findAllByActiveTrue()
        } catch (ex: Exception) {
            // Reading the active-assignments set must never bring down the
            // listener — log and skip the broadcast so the schedule write
            // still appears successful to the caller. Players will pick up
            // the change on their next periodic refresh / reconnect.
            log.warn(
                "PLAYLIST_UPDATE listener: failed to load active assignments for adId={}: {}",
                event.adId, ex.message,
            )
            return
        }

        if (activeAssignments.isEmpty()) {
            log.debug(
                "PLAYLIST_UPDATE listener: no active device assignments — skipping broadcast (adId={})",
                event.adId,
            )
            return
        }

        var totalDelivered = 0
        var devicesNotified = 0
        for (assignment in activeAssignments) {
            val payload = PlaylistUpdatedPayload(
                deviceId = assignment.deviceId,
                restaurantId = assignment.restaurantId,
                updatedAt = event.changedAt,
            )
            try {
                val delivered = publisher.publishPlaylistUpdated(assignment.deviceId, payload)
                if (delivered > 0) {
                    devicesNotified++
                    totalDelivered += delivered
                }
            } catch (ex: Exception) {
                // One bad device must not stop the fan-out. The publisher
                // already swallows per-emitter IO/state errors via the
                // registry's broadcast(); this catch covers any programmer
                // error (e.g. blank deviceId, which would be a data bug).
                log.warn(
                    "PLAYLIST_UPDATE fan-out failed for device={}: {}",
                    assignment.deviceId, ex.message,
                )
            }
        }
        log.info(
            "PLAYLIST_UPDATE fan-out adId={} advertiserId={} activeDevices={} devicesNotified={} totalEmittersDelivered={}",
            event.adId,
            event.advertiserId,
            activeAssignments.size,
            devicesNotified,
            totalDelivered,
        )
    }
}
