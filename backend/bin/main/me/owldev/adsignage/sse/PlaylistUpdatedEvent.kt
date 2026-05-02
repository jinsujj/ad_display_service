package me.owldev.adsignage.sse

import java.time.Instant

/**
 * Application event signalling that a playlist/schedule was created or
 * modified — i.e. the set of ads-and-windows that any active player should
 * fan out is no longer the same as it was a moment ago.
 *
 * Published by the ad-domain mutating services (currently [me.owldev.adsignage
 * .domain.ad.AdService.updateSchedule]) from inside the `@Transactional`
 * boundary that persists the change. Consumed by
 * [PlaylistUpdatedSseListener], which fans the event out as
 * `PLAYLIST_UPDATE` SSE messages to every device whose playlist could be
 * affected.
 *
 * # Why an application event (instead of a direct publisher call)
 *  - Keeps domain services free of HTTP / SSE plumbing — `AdService` does
 *    not depend on [SseEmitterRegistry] or [PlaylistEventPublisher].
 *  - Mirrors the established pattern set by [DeviceMappingChangedEvent] so
 *    every "something the player cares about changed" hop through the SSE
 *    bridge looks the same and is reasoned about the same way.
 *  - Lets the broadcast happen **strictly after** the DB transaction
 *    commits. The listener is wired with
 *    `@TransactionalEventListener(phase = AFTER_COMMIT)`, so:
 *      1. If the transaction rolls back, no SSE event is sent — players
 *         never see a phantom playlist refresh that contradicts persisted
 *         state.
 *      2. When the player refetches the playlist on receiving the
 *         PLAYLIST_UPDATE event, the new schedule is already committed and
 *         visible to the read.
 *
 * # Affected device set semantics
 * In the current data model an [me.owldev.adsignage.domain.ad.Ad] is owned
 * by an advertiser and is not scoped to a single restaurant — every active
 * player includes every ad in its rotation. The "affected device" set is
 * therefore "every device that currently has an active assignment". The
 * listener resolves that set at delivery time so we don't need to materialise
 * it on the publishing side.
 *
 * Forward-compat: when ads gain a per-restaurant scope, this event will
 * carry a `restaurantId` (or list of them) and the listener will narrow
 * delivery to devices assigned to those restaurants. Adding the field is a
 * backwards-compatible change because the listener is the only consumer.
 *
 * @property advertiserId the advertiser whose ad changed — included for
 *   audit/log correlation; not currently used to scope delivery.
 * @property adId         the ad row whose schedule changed — included for
 *   audit/log correlation and so the SSE payload can echo it.
 * @property changedAt    when the change was committed (publish time).
 */
data class PlaylistUpdatedEvent(
    val advertiserId: String,
    val adId: String,
    val changedAt: Instant = Instant.now(),
)
