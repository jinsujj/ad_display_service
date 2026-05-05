package me.owldev.adsignage.bounded.context.assignment.domain.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import me.owldev.adsignage.bounded.context.assignment.domain.model.DeviceAssignment
import java.time.Instant

/**
 * `POST /api/devices/{id}/assignment` 의 요청 본문.
 */
data class CreateAssignmentRequest(
    @field:NotBlank(message = "restaurantId must not be blank")
    @field:Size(max = 36, message = "restaurantId must be at most 36 characters")
    val restaurantId: String,
)

/**
 * `PUT /api/devices/{id}/assignment` 의 요청 본문.
 */
data class UpdateAssignmentRequest(
    @field:NotBlank(message = "restaurantId must not be blank")
    @field:Size(max = 36, message = "restaurantId must be at most 36 characters")
    val restaurantId: String,
)

/**
 * `PATCH /api/devices/{deviceId}/restaurant` (Sub-AC 50101.1) 요청 본문.
 */
data class UpdateDeviceRestaurantRequest(
    @field:NotBlank(message = "restaurantId must not be blank")
    @field:Size(max = 36, message = "restaurantId must be at most 36 characters")
    val restaurantId: String,
)

/**
 * `POST` 와 `PUT /api/devices/{id}/assignment`,
 * 그리고 `PATCH /api/devices/{deviceId}/restaurant` (Sub-AC 50101.1)의 응답 본문.
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
