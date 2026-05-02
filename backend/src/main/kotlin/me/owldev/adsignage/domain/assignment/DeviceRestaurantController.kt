package me.owldev.adsignage.domain.assignment

import jakarta.validation.Valid
import me.owldev.adsignage.domain.assignment.dto.AssignmentResponse
import me.owldev.adsignage.domain.assignment.dto.UpdateDeviceRestaurantRequest
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Sub-AC 50101.1 — `PATCH /api/devices/{deviceId}/restaurant`.
 *
 * Lightweight, partial-update entry point for remapping a device to a
 * different restaurant. The PATCH-on-named-subresource shape reads as
 * "modify the device's `restaurant` association" and is what the admin UI's
 * inline remap form targets in the demo.
 *
 * Why a sibling controller (vs. an extra method on
 * [DeviceAssignmentController])? The existing controller pins its class-level
 * mapping at `/api/devices/{id}/assignment` so that POST/PUT both share the
 * same path prefix. A PATCH on `…/restaurant` lives at a *different* path,
 * and Spring's `@RequestMapping` does not let one controller class fan out
 * to multiple base paths cleanly. Splitting the controller keeps each
 * route's contract self-contained and makes it easy to add a future
 * `DELETE …/restaurant` (un-assign) without touching the assignment CRUD
 * surface.
 *
 * HTTP contract:
 *  - 200 OK on success — body = the new active [AssignmentResponse]
 *  - 400 Bad Request on validation failure (handled by GlobalExceptionHandler)
 *  - 404 Not Found if the deviceId or restaurantId is unknown
 *
 * Behavior:
 *  - Delegates to [DeviceAssignmentService.updateAssignment], which
 *    atomically deactivates any existing active row and inserts a new
 *    active one in a single transaction. This means PATCH is also the
 *    correct verb when the device is currently unassigned: the service
 *    treats "no existing active row" as a no-op deactivate and then
 *    inserts the new active row.
 *  - On success, the service publishes a `DeviceMappingChangedEvent` which
 *    the SSE bridge listener turns into a `MAPPING_CHANGED` push to every
 *    player connected for that device — this is what powers demo
 *    scenario #3 (real-time remap).
 *
 * Authorization is intentionally permissive in the hackathon build —
 * SecurityConfig opens the path matcher `/api/devices/{star}/restaurant`
 * (single-segment wildcard) alongside the sibling `…/assignment` route as a
 * transitional carve-out. Finer-grained checks land in a later
 * auth-and-isolation pass.
 */
@RestController
@RequestMapping("/api/devices/{deviceId}/restaurant")
class DeviceRestaurantController(
    private val assignmentService: DeviceAssignmentService,
) {

    private val log = LoggerFactory.getLogger(DeviceRestaurantController::class.java)

    /**
     * Remaps the device whose path id is [deviceId] to point at the
     * restaurant carried by [body]. Returns the new active assignment.
     *
     * The PATCH verb is chosen rather than PUT because the request body
     * carries only the *one* mutable field of the device-restaurant
     * association — the route is partial-update by intent.
     */
    @PatchMapping
    fun updateRestaurant(
        @PathVariable("deviceId") deviceId: String,
        @Valid @RequestBody body: UpdateDeviceRestaurantRequest,
    ): ResponseEntity<AssignmentResponse> {
        log.info(
            "PATCH /api/devices/{}/restaurant restaurantId={}",
            deviceId,
            body.restaurantId,
        )
        val saved = assignmentService.updateAssignment(deviceId, body.restaurantId)
        return ResponseEntity.ok(AssignmentResponse.from(saved))
    }
}
