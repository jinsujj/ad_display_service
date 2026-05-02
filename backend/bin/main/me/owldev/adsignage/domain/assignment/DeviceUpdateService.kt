package me.owldev.adsignage.domain.assignment

import me.owldev.adsignage.domain.assignment.dto.DeviceResponse
import me.owldev.adsignage.domain.assignment.dto.UpdateDeviceRequest
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * AC 9, Sub-AC 1 — service that executes the partial-update for
 * `PATCH /api/devices/{deviceId}`.
 *
 * Owns the *composition* of per-field updates, not the per-field
 * implementation. Each individual update is delegated to the existing
 * domain service that already knows how to apply it (today: only
 * [DeviceAssignmentService] for `restaurantId`; in the future: a sibling
 * service for screen/group fields once the V10 `devices` table grows those
 * columns). This keeps the PATCH endpoint free of cross-cutting
 * orchestration logic and lets the per-field flows keep their own SSE
 * publishing / audit semantics.
 *
 * Atomicity scope: the whole patch runs inside a single `@Transactional`
 * boundary. If the caller PATCHes `restaurantId` *and* a future
 * `screenName`, either both stick or both roll back — there is no
 * half-applied PATCH on the wire.
 *
 * No-op semantics: the controller has already enforced "at least one
 * field present" via [UpdateDeviceRequest.isEmpty]; this service can
 * therefore assume at least one branch fires.
 */
@Service
class DeviceUpdateService(
    private val assignmentService: DeviceAssignmentService,
    private val deviceLookup: DeviceLookup,
) {

    private val log = LoggerFactory.getLogger(DeviceUpdateService::class.java)

    /**
     * Applies the partial [request] to the device whose id is [deviceId].
     *
     * Field-by-field flow:
     *  - `restaurantId` → delegates to
     *    [DeviceAssignmentService.updateAssignment], which atomically
     *    deactivates any existing active row, inserts a new active row,
     *    and publishes a `DeviceMappingChangedEvent` consumed by the SSE
     *    bridge.
     *  - `screenName` / `groupName` → not yet persisted at this point in
     *    the build (the V10 `devices` table does not yet carry the
     *    corresponding columns). The service logs the request and
     *    raises [DeviceFieldUnsupportedException] so the controller can
     *    surface a typed 422 instead of silently dropping the field. A
     *    future sub-AC will add the columns and replace the throw with
     *    an UPDATE.
     *
     * @throws DeviceNotFoundException     if no row exists in `devices` for [deviceId]
     * @throws RestaurantNotFoundException if [request.restaurantId] is non-null and unknown
     * @throws DeviceFieldUnsupportedException if [request.screenName] or [request.groupName]
     *         is non-null (transitional; see field-level flow above)
     */
    @Transactional
    fun applyPatch(deviceId: String, request: UpdateDeviceRequest): DeviceResponse {
        require(deviceId.isNotBlank()) { "deviceId must not be blank" }

        // Validate parent exists up front so a PATCH that touches
        // *only* fields we don't yet support still fails as 404 rather
        // than 422 — "device not found" is the more useful answer.
        if (!deviceLookup.exists(deviceId)) {
            throw DeviceNotFoundException(deviceId)
        }

        var currentAssignment: DeviceAssignment? =
            assignmentService.findCurrentAssignment(deviceId)

        // 1) restaurantId: delegate to the existing remap flow so we
        //    inherit its atomic deactivate-then-insert + SSE publish
        //    semantics.
        if (request.restaurantId != null) {
            currentAssignment = assignmentService.updateAssignment(
                deviceId = deviceId,
                newRestaurantId = request.restaurantId,
            )
            log.info(
                "applyPatch: device={} restaurantId={} (assignmentId={})",
                deviceId, request.restaurantId, currentAssignment.id,
            )
        }

        // 2) screenName / groupName: schema not yet in place. Surface
        //    a typed exception so the API contract distinguishes
        //    "the route exists but the column doesn't yet" from a 500.
        if (request.screenName != null) {
            log.info(
                "applyPatch: device={} screenName={} requested but column not yet provisioned",
                deviceId, request.screenName,
            )
            throw DeviceFieldUnsupportedException("screenName")
        }
        if (request.groupName != null) {
            log.info(
                "applyPatch: device={} groupName={} requested but column not yet provisioned",
                deviceId, request.groupName,
            )
            throw DeviceFieldUnsupportedException("groupName")
        }

        return DeviceResponse.fromAssignment(deviceId, currentAssignment)
    }
}
