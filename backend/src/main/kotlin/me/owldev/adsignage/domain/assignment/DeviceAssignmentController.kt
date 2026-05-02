package me.owldev.adsignage.domain.assignment

import jakarta.validation.Valid
import me.owldev.adsignage.domain.assignment.dto.AssignmentResponse
import me.owldev.adsignage.domain.assignment.dto.CreateAssignmentRequest
import me.owldev.adsignage.domain.assignment.dto.UpdateAssignmentRequest
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * REST endpoints for the device → restaurant assignment lifecycle.
 *
 * Sub-AC 3 scope:
 *  - `POST /api/devices/{id}/assignment` — create the (first) active
 *    assignment for a device. Idempotent: if the device already has an active
 *    assignment, the service deactivates it and inserts a new active row,
 *    matching the wire-equivalent semantics of PUT.
 *  - `PUT /api/devices/{id}/assignment` — remap a device to a different
 *    restaurant. This is the SSE-driven entry point for demo scenario #3.
 *
 * HTTP contract:
 *  - 201 Created on POST success (response body = the new active assignment)
 *  - 200 OK      on PUT success
 *  - 400 Bad Request on validation failure (handled by GlobalExceptionHandler)
 *  - 404 Not Found if the device_id or restaurant_id is unknown
 *
 * Authorization is intentionally permissive in the hackathon build — the
 * SecurityConfig opens these routes; finer-grained checks land in a later
 * sub-AC alongside JWT login.
 */
@RestController
@RequestMapping("/api/devices/{id}/assignment")
class DeviceAssignmentController(
    private val assignmentService: DeviceAssignmentService,
) {

    private val log = LoggerFactory.getLogger(DeviceAssignmentController::class.java)

    /**
     * Creates the active assignment for the device whose path id is [id].
     *
     * Returns 201 Created with the persisted assignment. If the device already
     * has an active assignment, the service collapses to update semantics
     * (deactivate old + insert new) — the response still carries the *new*
     * active row, and the status remains 201 to keep the POST contract simple
     * for hackathon clients.
     */
    @PostMapping
    fun create(
        @PathVariable("id") deviceId: String,
        @Valid @RequestBody body: CreateAssignmentRequest,
    ): ResponseEntity<AssignmentResponse> {
        log.info("POST /api/devices/{}/assignment restaurantId={}", deviceId, body.restaurantId)
        val saved = assignmentService.createAssignment(deviceId, body.restaurantId)
        return ResponseEntity
            .status(HttpStatus.CREATED)
            .body(AssignmentResponse.from(saved))
    }

    /**
     * Updates (remaps) the active assignment for the device whose path id is
     * [id] to point at the [body]'s restaurant.
     *
     * Returns 200 OK with the new active assignment. The previously-active row
     * is deactivated atomically inside the service transaction so a device is
     * never observably in a half-mapped state.
     */
    @PutMapping
    fun update(
        @PathVariable("id") deviceId: String,
        @Valid @RequestBody body: UpdateAssignmentRequest,
    ): ResponseEntity<AssignmentResponse> {
        log.info("PUT /api/devices/{}/assignment restaurantId={}", deviceId, body.restaurantId)
        val saved = assignmentService.updateAssignment(deviceId, body.restaurantId)
        return ResponseEntity.ok(AssignmentResponse.from(saved))
    }
}
