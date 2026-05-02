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
 * 디바이스를 다른 음식점으로 리매핑하기 위한 가벼운 부분 업데이트 진입점.
 * 명명된 하위 리소스에 대한 PATCH 모양은 "디바이스의 `restaurant` 연관을
 * 수정"으로 읽히며, 데모에서 관리자 UI의 인라인 리매핑 폼이 대상으로
 * 하는 것임.
 *
 * 왜 ([DeviceAssignmentController]에 추가 메서드를 두지 않고) 형제 컨트롤러인가?
 * 기존 컨트롤러는 POST/PUT이 동일한 경로 접두사를 공유하도록 클래스 수준
 * 매핑을 `/api/devices/{id}/assignment`에 고정함. `…/restaurant`에 대한
 * PATCH는 *다른* 경로에 위치하며, 스프링의 `@RequestMapping`은 한
 * 컨트롤러 클래스가 여러 기본 경로로 깔끔하게 분기하는 것을 허용하지
 * 않음. 컨트롤러를 분리하면 각 라우트의 계약이 자기 완결적으로 유지되고
 * 추후 할당 CRUD 표면을 건드리지 않고 `DELETE …/restaurant`(할당 해제)를
 * 쉽게 추가할 수 있음.
 *
 * HTTP 계약:
 *  - 200 OK 성공 시 — 본문 = 새 활성 [AssignmentResponse]
 *  - 400 Bad Request 검증 실패 시 (GlobalExceptionHandler가 처리)
 *  - 404 Not Found deviceId 또는 restaurantId가 알려지지 않은 경우
 *
 * 동작:
 *  - [DeviceAssignmentService.updateAssignment]에 위임하고, 이는 단일
 *    트랜잭션에서 기존 활성 행을 원자적으로 비활성화하고 새 활성 행을
 *    삽입함. 따라서 디바이스가 현재 할당되지 않았을 때도 PATCH가 올바른
 *    동사: 서비스는 "기존 활성 행 없음"을 no-op 비활성화로 취급한 다음
 *    새 활성 행을 삽입.
 *  - 성공 시 서비스는 `DeviceMappingChangedEvent`를 발행하고, SSE 브리지
 *    리스너가 이를 해당 디바이스에 연결된 모든 플레이어로
 *    `MAPPING_CHANGED` 푸시로 변환 — 이것이 데모 시나리오 #3(실시간
 *    리매핑)을 동작시킴.
 *
 * 해커톤 빌드에서 인가는 의도적으로 관대 — SecurityConfig는 형제
 * `…/assignment` 라우트와 함께 과도기 예외로 경로 매처
 * `/api/devices/{star}/restaurant`(단일 세그먼트 와일드카드)를 열어둠.
 * 더 세밀한 검사는 추후 auth-and-isolation 패스에서 도착함.
 */
@RestController
@RequestMapping("/api/devices/{deviceId}/restaurant")
class DeviceRestaurantController(
    private val assignmentService: DeviceAssignmentService,
) {

    private val log = LoggerFactory.getLogger(DeviceRestaurantController::class.java)

    /**
     * 경로 id가 [deviceId]인 디바이스를 [body]가 운반하는 음식점을 가리키도록
     * 리매핑. 새 활성 할당을 반환.
     *
     * PUT이 아닌 PATCH 동사가 선택된 이유는 요청 본문이 device-restaurant
     * 연관의 *유일한* 가변 필드만 운반 — 라우트는 의도적으로 부분 업데이트.
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
