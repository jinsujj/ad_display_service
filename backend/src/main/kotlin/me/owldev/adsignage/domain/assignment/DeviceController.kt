package me.owldev.adsignage.domain.assignment

import jakarta.validation.Valid
import me.owldev.adsignage.domain.assignment.dto.DeviceResponse
import me.owldev.adsignage.domain.assignment.dto.UpdateDeviceRequest
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * AC 9, Sub-AC 1 — `PATCH /api/devices/{deviceId}`.
 *
 * Generic, partial-update entry point for the device record. Whereas the
 * sibling [DeviceRestaurantController] (PATCH …/restaurant) is purpose-built
 * for the single restaurant-remap use case, this controller is the umbrella
 * route the admin UI hits when it wants to change *any* mutable device field
 * — current and future. The same controller will absorb new fields (display
 * orientation, daypart override, screen label, group tag, …) as they land,
 * so the admin-side fetch shape stays a single PATCH instead of fanning out
 * into one named-subresource per field.
 *
 * Why a sibling controller (vs. extending [DeviceRestaurantController])?
 * That controller pins its class-level mapping at
 * `/api/devices/{deviceId}/restaurant` for the partial-update-of-the-named-
 * subresource shape. This route is *not* a subresource — it targets the
 * device entity itself. Spring's `@RequestMapping` does not let one
 * controller class fan out to multiple base paths cleanly, so we keep each
 * route's contract self-contained.
 *
 * HTTP contract:
 *  - 200 OK on success — body = the post-patch [DeviceResponse]
 *  - 400 Bad Request when the request body validates but contains no
 *    actionable fields, or when an individual field fails its own validation
 *    (delegated to [me.owldev.adsignage.web.GlobalExceptionHandler])
 *  - 404 Not Found if the deviceId or referenced restaurantId is unknown
 *
 * Behavior:
 *  - The request body is partial: only fields the caller actually wants to
 *    change appear. An absent key leaves the corresponding device field
 *    untouched. A blank string is rejected at validation time (clients
 *    should omit a key, not send `""`, to mean "no change").
 *  - When [UpdateDeviceRequest.restaurantId] is present, the service
 *    delegates to [DeviceAssignmentService.updateAssignment], which
 *    atomically deactivates any existing active row and inserts a new one,
 *    then publishes a `DeviceMappingChangedEvent` consumed by the SSE
 *    bridge — the same wire flow that powers demo scenario #3.
 *  - The response always echoes the device's current active assignment (if
 *    any) so callers can confirm the post-patch state in a single round
 *    trip without a follow-up GET.
 *
 * Authorization: the route is permitted in [SecurityConfig] alongside the
 * sibling `…/restaurant` and `…/assignment` carve-outs as a transitional
 * measure for the hackathon build. The auth-and-isolation pass that gates
 * the `/api/devices` admin CRUD prefix behind `ROLE_ADVERTISER` will pick
 * up this route too — no controller changes required.
 */
@RestController
@RequestMapping("/api/devices/{deviceId}")
class DeviceController(
    private val deviceUpdateService: DeviceUpdateService,
) {

    private val log = LoggerFactory.getLogger(DeviceController::class.java)

    /**
     * Applies the partial-update [body] to the device whose path id is
     * [deviceId]. Returns the post-patch [DeviceResponse], including the
     * resolved current active assignment so the admin UI can render the
     * outcome without a follow-up GET.
     *
     * The PATCH verb is chosen rather than PUT because the request body
     * carries an arbitrary subset of mutable fields — the route is partial
     * by design.
     */
    @PatchMapping
    fun update(
        @PathVariable("deviceId") deviceId: String,
        @Valid @RequestBody body: UpdateDeviceRequest,
    ): ResponseEntity<DeviceResponse> {
        log.info(
            "PATCH /api/devices/{} restaurantId={} screenName={} groupName={}",
            deviceId, body.restaurantId, body.screenName, body.groupName,
        )

        // Reject a syntactically valid but semantically empty body up
        // front. A zero-field PATCH is almost certainly a client bug
        // (e.g. an admin form submitted before any input). Returning 400
        // surfaces that immediately rather than silently 200ing with the
        // unchanged current state.
        if (body.isEmpty()) {
            throw IllegalArgumentException(
                "PATCH body must include at least one updatable field " +
                    "(restaurantId, screenName, groupName)",
            )
        }

        val response = deviceUpdateService.applyPatch(deviceId, body)
        return ResponseEntity.ok(response)
    }
}
