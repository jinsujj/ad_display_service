package me.owldev.adsignage.domain.assignment

import me.owldev.adsignage.sse.DeviceMappingChangedEvent
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Service that owns the device → restaurant assignment lifecycle.
 *
 * Sub-AC 2 scope:
 *  - [createAssignment] — create the first/only active assignment for a device.
 *  - [updateAssignment] — atomically remap a device to a different restaurant.
 *  - [getCurrentAssignment] — fetch the current active assignment.
 *  - Validates that referenced device_id / restaurant_id exist before writing.
 *
 * Concurrency note: [updateAssignment] and [createAssignment] run in a single
 * transaction so the "deactivate old + insert new" pair is atomic — there is
 * never a moment where a device has zero or two active rows.
 *
 * SSE note (sub-AC 50102.2): on every successful create / update, the service
 * publishes a [DeviceMappingChangedEvent] so the SSE bridge layer can push a
 * MAPPING_CHANGED event down to every player connected for that device. The
 * domain service stays free of HTTP / SSE concerns — it just announces
 * "this mapping changed", and the [me.owldev.adsignage.sse] module decides
 * how that gets delivered to clients. Delivery happens **after the DB update
 * commits** — the listener is wired with
 * `@TransactionalEventListener(AFTER_COMMIT)`, so a rolled-back transaction
 * never produces a phantom remap event on the wire.
 */
@Service
class DeviceAssignmentService(
    private val assignmentRepository: DeviceAssignmentRepository,
    private val deviceLookup: DeviceLookup,
    private val restaurantLookup: RestaurantLookup,
    private val eventPublisher: ApplicationEventPublisher,
) {

    private val log = LoggerFactory.getLogger(DeviceAssignmentService::class.java)

    /**
     * Creates a new active assignment for [deviceId] → [restaurantId].
     *
     * If the device already has an active assignment, this delegates to
     * [updateAssignment] (deactivate-then-insert) so callers can use this
     * method as an idempotent "set current assignment" entry point.
     *
     * @throws DeviceNotFoundException     if no row exists in `devices` for [deviceId]
     * @throws RestaurantNotFoundException if no row exists in `restaurants` for [restaurantId]
     */
    @Transactional
    fun createAssignment(deviceId: String, restaurantId: String): DeviceAssignment {
        validateReferencesExist(deviceId, restaurantId)

        val existing = assignmentRepository.findByDeviceIdAndActiveTrue(deviceId)
        if (existing.isPresent) {
            log.info(
                "createAssignment: device {} already has active assignment {} → delegating to updateAssignment",
                deviceId,
                existing.get().id,
            )
            return updateAssignmentInternal(deviceId, restaurantId)
        }

        val saved = assignmentRepository.save(
            DeviceAssignment(deviceId = deviceId, restaurantId = restaurantId),
        )
        log.info(
            "createAssignment: device={} restaurant={} assignmentId={}",
            deviceId, restaurantId, saved.id,
        )
        publishMappingChanged(saved)
        return saved
    }

    /**
     * Remaps [deviceId] to [newRestaurantId]. Deactivates the existing active
     * row (if any) and inserts a new active row in the same transaction.
     *
     * Returns the newly-created active [DeviceAssignment].
     *
     * @throws DeviceNotFoundException     if no row exists in `devices` for [deviceId]
     * @throws RestaurantNotFoundException if no row exists in `restaurants` for [newRestaurantId]
     */
    @Transactional
    fun updateAssignment(deviceId: String, newRestaurantId: String): DeviceAssignment {
        validateReferencesExist(deviceId, newRestaurantId)
        return updateAssignmentInternal(deviceId, newRestaurantId)
    }

    /**
     * Returns the current active assignment for [deviceId].
     *
     * @throws AssignmentNotFoundException if the device has no active assignment
     */
    @Transactional(readOnly = true)
    fun getCurrentAssignment(deviceId: String): DeviceAssignment =
        assignmentRepository.findByDeviceIdAndActiveTrue(deviceId)
            .orElseThrow { AssignmentNotFoundException(deviceId) }

    /**
     * Returns the current active assignment for [deviceId], or `null`
     * if the device is currently unassigned.
     */
    @Transactional(readOnly = true)
    fun findCurrentAssignment(deviceId: String): DeviceAssignment? =
        assignmentRepository.findByDeviceIdAndActiveTrue(deviceId).orElse(null)

    // -------------------------------------------------------------------------
    // internals
    // -------------------------------------------------------------------------

    private fun validateReferencesExist(deviceId: String, restaurantId: String) {
        require(deviceId.isNotBlank()) { "deviceId must not be blank" }
        require(restaurantId.isNotBlank()) { "restaurantId must not be blank" }
        if (!deviceLookup.exists(deviceId)) throw DeviceNotFoundException(deviceId)
        if (!restaurantLookup.exists(restaurantId)) throw RestaurantNotFoundException(restaurantId)
    }

    /**
     * Atomic deactivate-then-insert. Caller is responsible for having already
     * validated that [deviceId] and [newRestaurantId] exist.
     */
    private fun updateAssignmentInternal(deviceId: String, newRestaurantId: String): DeviceAssignment {
        val deactivated = assignmentRepository.deactivateCurrentForDevice(deviceId)
        // Flush via saveAndFlush isn't strictly needed — the @Modifying query
        // already executes against the DB; but we rely on the transaction
        // boundary to make the whole pair atomic.
        val saved = assignmentRepository.save(
            DeviceAssignment(deviceId = deviceId, restaurantId = newRestaurantId),
        )
        log.info(
            "updateAssignment: device={} → restaurant={} (deactivatedRows={}, newAssignmentId={})",
            deviceId, newRestaurantId, deactivated, saved.id,
        )
        publishMappingChanged(saved)
        return saved
    }

    /**
     * Publishes the [DeviceMappingChangedEvent] that the SSE bridge listens
     * for. The publish call is made from inside the `@Transactional` method,
     * but the listener
     * ([me.owldev.adsignage.sse.DeviceMappingChangedSseListener]) is bound to
     * [org.springframework.transaction.event.TransactionPhase.AFTER_COMMIT]
     * via `@TransactionalEventListener`, so the SSE broadcast does not run
     * until the assignment row has been durably committed to the database.
     *
     * Sub-AC 50102.2 contract:
     *  - Subscribers receive the remapping event **after the DB update commits**
     *    — never before, never on a rolled-back write.
     *
     * Failures inside listeners must not affect the assignment itself. Because
     * the listener fires after commit, the transaction is already closed when
     * the broadcast runs — but the publish call below is still wrapped in a
     * try/catch as a belt-and-braces guard against any pre-commit listeners
     * that future ACs might attach to the same event type.
     */
    private fun publishMappingChanged(saved: DeviceAssignment) {
        try {
            eventPublisher.publishEvent(
                DeviceMappingChangedEvent(
                    deviceId = saved.deviceId,
                    restaurantId = saved.restaurantId,
                    assignmentId = saved.id,
                    assignedAt = saved.assignedAt,
                ),
            )
        } catch (ex: Exception) {
            log.warn(
                "Failed to publish DeviceMappingChangedEvent for device={}: {}",
                saved.deviceId, ex.message,
            )
        }
    }
}
