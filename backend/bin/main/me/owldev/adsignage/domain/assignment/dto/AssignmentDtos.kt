package me.owldev.adsignage.domain.assignment.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import me.owldev.adsignage.domain.assignment.DeviceAssignment
import java.time.Instant

/**
 * Request body for `POST /api/devices/{id}/assignment`.
 *
 * Carries the target restaurant for a device that does not yet have a current
 * assignment (or whose existing active assignment should be replaced — the
 * service treats create as idempotent "set current assignment").
 *
 * Ontology mapping:
 *  - [restaurantId] → device_restaurant_id (FK → restaurants.restaurant_id)
 */
data class CreateAssignmentRequest(
    @field:NotBlank(message = "restaurantId must not be blank")
    @field:Size(max = 36, message = "restaurantId must be at most 36 characters")
    val restaurantId: String,
)

/**
 * Request body for `PUT /api/devices/{id}/assignment`.
 *
 * Carries the new target restaurant for a device. The service deactivates the
 * previously-active row (if any) and inserts a new active row in a single
 * transaction — this is the SSE-driven remap entry point for demo scenario #3.
 *
 * Same shape as [CreateAssignmentRequest] today; kept as a separate type so
 * that PUT-specific fields can be added later (e.g. an `expectedAssignmentId`
 * for optimistic concurrency) without breaking POST callers.
 */
data class UpdateAssignmentRequest(
    @field:NotBlank(message = "restaurantId must not be blank")
    @field:Size(max = 36, message = "restaurantId must be at most 36 characters")
    val restaurantId: String,
)

/**
 * Request body for `PATCH /api/devices/{deviceId}/restaurant` (Sub-AC 50101.1).
 *
 * Carries the new target restaurant for a device under a partial-update verb.
 * Semantically identical to [UpdateAssignmentRequest] today — both ultimately
 * call the same service-layer atomic deactivate-then-insert — but the route
 * + verb pair (`PATCH …/restaurant`) is the one demanded by the AC contract:
 * the PATCH-on-a-named-subresource shape reads as "modify the device's
 * `restaurant` association" and is what the admin UI's lightweight remap form
 * targets.
 *
 * Kept as a separate class (rather than reusing [UpdateAssignmentRequest]) so
 * that route-specific validation / fields (e.g. `expectedRestaurantId` for
 * optimistic concurrency, or a `null` to detach) can be added later without
 * breaking the older `PUT …/assignment` callers.
 *
 * Ontology mapping:
 *  - [restaurantId] → device_restaurant_id (FK → restaurants.restaurant_id)
 */
data class UpdateDeviceRestaurantRequest(
    @field:NotBlank(message = "restaurantId must not be blank")
    @field:Size(max = 36, message = "restaurantId must be at most 36 characters")
    val restaurantId: String,
)

/**
 * Request body for `PATCH /api/devices/{deviceId}` (AC 9, Sub-AC 1).
 *
 * Generic partial-update entry point for the device record. Every field is
 * **optional** — callers send only the fields they want to change, keeping the
 * verb-route pair true to PATCH semantics. The presence of a key in the JSON
 * body is what triggers the update; an absent key leaves the corresponding
 * device field untouched.
 *
 * Fields supported in this sub-AC:
 *  - [restaurantId] — remap the device's active restaurant assignment. When
 *    present, the service delegates to the existing
 *    [me.owldev.adsignage.domain.assignment.DeviceAssignmentService.updateAssignment]
 *    flow (atomic deactivate-then-insert + SSE MAPPING_CHANGED publish).
 *  - [screenName]   — free-form display label for the physical screen the
 *    device is mounted on. Useful for the admin UI's grouping (e.g. "Lobby
 *    TV", "Counter Display"). Stored on the device record itself rather than
 *    on the assignment, so it survives restaurant remaps.
 *  - [groupName]    — free-form group label so the admin UI can bucket
 *    devices for bulk operations (e.g. "north-store-fleet").
 *
 * Note on `screenName` / `groupName`: the V10 `devices` table is owned by a
 * sibling sub-AC and may not yet carry these columns. The controller +
 * service still accept the keys so the wire contract is forward-compatible;
 * a missing column on the DB side surfaces as a typed
 * [me.owldev.adsignage.domain.assignment.DeviceFieldUnsupportedException]
 * (HTTP 422) rather than a 500 from JDBC. Callers in the demo build typically
 * only send `restaurantId`.
 *
 * Validation: every present field is independently validated. Blank string
 * values are rejected (use field omission to mean "no change") so the
 * contract distinguishes "set to empty" from "leave alone" — the latter is
 * the only supported semantic for now.
 */
data class UpdateDeviceRequest(
    @field:Size(min = 1, max = 36, message = "restaurantId must be 1..36 characters when present")
    val restaurantId: String? = null,

    @field:Size(min = 1, max = 128, message = "screenName must be 1..128 characters when present")
    val screenName: String? = null,

    @field:Size(min = 1, max = 128, message = "groupName must be 1..128 characters when present")
    val groupName: String? = null,
) {
    /**
     * `true` if the request body carried no fields the service knows how to
     * apply — used by the controller to short-circuit a no-op PATCH with a
     * 400 rather than silently returning the current state.
     */
    fun isEmpty(): Boolean =
        restaurantId == null && screenName == null && groupName == null
}

/**
 * Response body for `PATCH /api/devices/{deviceId}` (AC 9, Sub-AC 1).
 *
 * Returns the post-patch view of the device — the resolved active assignment
 * plus any device-level fields that were updated in the same request. The
 * shape is intentionally a superset of [AssignmentResponse] so existing
 * admin-UI code that already consumes assignment payloads can switch to the
 * generic PATCH endpoint without a parser rewrite.
 *
 * `restaurantId` may be `null` if the device has no active assignment after
 * the patch (e.g. a future sub-AC adds an "unassign" path). For the current
 * sub-AC, callers that PATCH `restaurantId` will always see it echoed here.
 */
data class DeviceResponse(
    val deviceId: String,
    val restaurantId: String?,
    val assignmentId: String?,
    val assignedAt: Instant?,
    val screenName: String? = null,
    val groupName: String? = null,
) {
    companion object {
        fun fromAssignment(deviceId: String, entity: DeviceAssignment?): DeviceResponse =
            DeviceResponse(
                deviceId = deviceId,
                restaurantId = entity?.restaurantId,
                assignmentId = entity?.id,
                assignedAt = entity?.assignedAt,
            )
    }
}

/**
 * Response body for both `POST` and `PUT /api/devices/{id}/assignment`,
 * as well as `PATCH /api/devices/{deviceId}/restaurant` (Sub-AC 50101.1).
 *
 * Mirrors the persisted [DeviceAssignment] as a stable wire contract — keeps
 * Hibernate-managed state out of the controller boundary and lets the JSON
 * field set evolve independently of the entity column layout.
 */
data class AssignmentResponse(
    val assignmentId: String,
    val deviceId: String,
    val restaurantId: String,
    val assignedAt: Instant,
    val active: Boolean,
) {
    companion object {
        fun from(entity: DeviceAssignment): AssignmentResponse = AssignmentResponse(
            assignmentId = entity.id,
            deviceId = entity.deviceId,
            restaurantId = entity.restaurantId,
            assignedAt = entity.assignedAt,
            active = entity.active,
        )
    }
}
