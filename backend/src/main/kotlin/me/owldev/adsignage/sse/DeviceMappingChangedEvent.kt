package me.owldev.adsignage.sse

import java.time.Instant

/**
 * Application event signalling that a device's restaurant assignment changed.
 *
 * Published by `DeviceAssignmentService` from inside the `@Transactional`
 * method that writes the new active assignment row. Consumed by
 * [DeviceMappingChangedSseListener], which fans the event out to every
 * connected SSE emitter for [deviceId].
 *
 * Why an application event (instead of a direct registry call from the service):
 *  - Keeps the domain service free of HTTP / SSE concerns — the service can
 *    be unit-tested without booting the web layer.
 *  - Lets sibling features (audit log, push-notification, metrics) hook in
 *    without modifying the service.
 *  - Lets the broadcast happen **strictly after** the DB transaction commits.
 *    The listener is wired with
 *    `@TransactionalEventListener(phase = AFTER_COMMIT)`, so Spring delays
 *    invocation until the transaction has been committed. This guarantees
 *    that:
 *      1. If the transaction rolls back, no SSE event is sent — players
 *         never see a phantom remap that contradicts persisted state.
 *      2. When the player refetches the playlist on receiving
 *         MAPPING_CHANGED, the new assignment row is already committed and
 *         visible to the read.
 */
data class DeviceMappingChangedEvent(
    val deviceId: String,
    val restaurantId: String,
    val assignmentId: String,
    val assignedAt: Instant,
)
