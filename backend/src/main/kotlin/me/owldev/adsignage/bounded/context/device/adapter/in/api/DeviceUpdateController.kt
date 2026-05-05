package me.owldev.adsignage.bounded.context.device.adapter.`in`.api

import jakarta.validation.Valid
import me.owldev.adsignage.bounded.context.device.application.service.DeviceUpdateService
import me.owldev.adsignage.bounded.context.device.domain.dto.UpdateDeviceRequest
import me.owldev.adsignage.bounded.context.device.domain.dto.UpdateDeviceResponse
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
 * 디바이스 레코드를 위한 일반적이고 부분적 업데이트 진입점. 형제
 * `DeviceRestaurantController`(PATCH …/restaurant) 가 단일 음식점 리매핑
 * 사용 사례 전용으로 만들어진 반면, 이 컨트롤러는 관리자 UI 가 *모든*
 * 가변 디바이스 필드(현재와 미래)를 변경하려 할 때 도달하는 우산 라우트.
 *
 * HTTP 계약:
 *  - 200 OK 성공 시 — 본문 = 패치 후 [UpdateDeviceResponse]
 *  - 400 Bad Request 요청 본문은 유효하지만 실행 가능한 필드가 없거나,
 *    개별 필드가 자체 검증에 실패할 때
 *  - 404 Not Found deviceId 또는 참조된 restaurantId가 알려지지 않은 경우
 */
@RestController
@RequestMapping("/api/devices/{deviceId}")
class DeviceUpdateController(
    private val deviceUpdateService: DeviceUpdateService,
) {

    private val log = LoggerFactory.getLogger(DeviceUpdateController::class.java)

    @PatchMapping
    fun update(
        @PathVariable("deviceId") deviceId: String,
        @Valid @RequestBody body: UpdateDeviceRequest,
    ): ResponseEntity<UpdateDeviceResponse> {
        log.info(
            "PATCH /api/devices/{} restaurantId={} screenName={} groupName={}",
            deviceId, body.restaurantId, body.screenName, body.groupName,
        )

        // 구문은 유효하지만 시맨틱이 비어 있는 본문을 미리 거부. 0필드
        // PATCH는 거의 확실히 클라이언트 버그.
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
