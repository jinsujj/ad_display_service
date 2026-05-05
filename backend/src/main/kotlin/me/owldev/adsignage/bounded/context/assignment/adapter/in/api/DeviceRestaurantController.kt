package me.owldev.adsignage.bounded.context.assignment.adapter.`in`.api

import jakarta.validation.Valid
import me.owldev.adsignage.bounded.context.assignment.application.service.DeviceAssignmentService
import me.owldev.adsignage.bounded.context.assignment.domain.dto.AssignmentResponse
import me.owldev.adsignage.bounded.context.assignment.domain.dto.UpdateDeviceRestaurantRequest
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
 * 디바이스를 다른 음식점으로 리매핑하기 위한 가벼운 부분 업데이트 진입점.
 * 명명된 하위 리소스에 대한 PATCH 모양은 "디바이스의 `restaurant` 연관을
 * 수정"으로 읽히며, 데모에서 관리자 UI의 인라인 리매핑 폼이 대상으로 함.
 */
@RestController
@RequestMapping("/api/devices/{deviceId}/restaurant")
class DeviceRestaurantController(
    private val assignmentService: DeviceAssignmentService,
) {

    private val log = LoggerFactory.getLogger(DeviceRestaurantController::class.java)

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
