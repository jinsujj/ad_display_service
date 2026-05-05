package me.owldev.adsignage.bounded.context.assignment.adapter.`in`.api

import jakarta.validation.Valid
import me.owldev.adsignage.bounded.context.assignment.application.service.DeviceAssignmentService
import me.owldev.adsignage.bounded.context.assignment.domain.dto.AssignmentResponse
import me.owldev.adsignage.bounded.context.assignment.domain.dto.CreateAssignmentRequest
import me.owldev.adsignage.bounded.context.assignment.domain.dto.UpdateAssignmentRequest
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
 * 디바이스 → 음식점 할당 라이프사이클을 위한 REST 엔드포인트.
 *
 *  - `POST /api/devices/{id}/assignment` — 디바이스에 대해 (첫 번째) 활성
 *    할당을 생성. 멱등(Idempotent).
 *  - `PUT /api/devices/{id}/assignment` — 디바이스를 다른 음식점으로
 *    리매핑. SSE 기반 진입점.
 */
@RestController
@RequestMapping("/api/devices/{id}/assignment")
class DeviceAssignmentController(
    private val assignmentService: DeviceAssignmentService,
) {

    private val log = LoggerFactory.getLogger(DeviceAssignmentController::class.java)

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
