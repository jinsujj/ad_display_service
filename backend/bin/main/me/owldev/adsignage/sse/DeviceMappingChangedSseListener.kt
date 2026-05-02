package me.owldev.adsignage.sse

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

/**
 * Bridges [DeviceMappingChangedEvent]s into the SSE wire by broadcasting a
 * [SseEventNames.MAPPING_CHANGED] event to every emitter currently registered
 * for the affected device.
 *
 * This is the listener half of the publisher/listener pair that powers
 * demo scenario #3 (real-time device-to-restaurant remapping):
 *
 *  1. Admin calls `PUT /api/devices/{id}/assignment` with a new restaurant.
 *  2. `DeviceAssignmentService.updateAssignment` writes the new active row
 *     and publishes [DeviceMappingChangedEvent] from inside the transaction.
 *  3. The transaction commits — only **after** the row is durable does
 *     Spring invoke this listener (it is wired with
 *     [TransactionalEventListener] at phase [TransactionPhase.AFTER_COMMIT]).
 *  4. This listener calls [DeviceSseRegistry.broadcast] for the affected
 *     deviceId.
 *  5. The Android player page (route `/player/{deviceId}`), which has a
 *     long-lived SSE subscription, receives the event and refetches its
 *     playlist for the new restaurant.
 *
 * # Why AFTER_COMMIT and not synchronous `@EventListener`
 *  - **Consistency with the DB**: if the assignment transaction rolls back
 *    (e.g. a constraint violation surfaces at flush time), no MAPPING_CHANGED
 *    event is sent, so players never see a phantom remap that does not match
 *    persisted state.
 *  - **Refetch ordering**: when a player receives MAPPING_CHANGED and
 *    immediately fetches `GET /api/playlist`, the assignment row is already
 *    committed and visible to the playlist query — so the refetch returns
 *    the new restaurant's playlist, not the previous one.
 *  - **Safe per-listener failure isolation**: per-emitter SSE send failures
 *    cannot roll back the assignment because the listener fires post-commit.
 *
 * # `fallbackExecution = true`
 * If the event is published *outside* of a transaction (e.g. a test that
 * calls the service without `@Transactional`, or a future ad-hoc admin tool
 * that bypasses the transactional service), Spring would normally drop the
 * event silently because there is no transaction phase to bind to. Setting
 * `fallbackExecution = true` makes Spring invoke the listener immediately in
 * that case so the broadcast still happens. The integration tests rely on
 * this behavior.
 */
@Component
class DeviceMappingChangedSseListener(
    private val registry: DeviceSseRegistry,
) {

    private val log = LoggerFactory.getLogger(DeviceMappingChangedSseListener::class.java)

    @TransactionalEventListener(
        phase = TransactionPhase.AFTER_COMMIT,
        fallbackExecution = true,
    )
    fun onMappingChanged(event: DeviceMappingChangedEvent) {
        val payload = MappingChangedPayload(
            deviceId = event.deviceId,
            restaurantId = event.restaurantId,
            assignmentId = event.assignmentId,
            assignedAt = event.assignedAt,
        )
        val sseEvent = SseEmitter.event()
            .name(SseEventNames.MAPPING_CHANGED)
            .data(payload)
            .id(event.assignmentId)

        val delivered = registry.broadcast(event.deviceId, sseEvent)
        log.info(
            "MAPPING_CHANGED → device={} newRestaurant={} delivered={}",
            event.deviceId,
            event.restaurantId,
            delivered,
        )
    }
}
