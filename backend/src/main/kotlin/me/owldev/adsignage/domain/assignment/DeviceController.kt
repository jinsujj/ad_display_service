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
 * 디바이스 레코드를 위한 일반적이고 부분적 업데이트 진입점. 형제
 * [DeviceRestaurantController](PATCH …/restaurant)가 단일 음식점 리매핑
 * 사용 사례 전용으로 만들어진 반면, 이 컨트롤러는 관리자 UI가 *모든*
 * 가변 디바이스 필드(현재와 미래)를 변경하려 할 때 도달하는 우산 라우트.
 * 같은 컨트롤러가 새 필드(디스플레이 방향, daypart 오버라이드, 화면
 * 레이블, 그룹 태그 등)가 도착할 때마다 흡수할 것이므로 관리자 측 fetch
 * 모양은 필드당 하나의 명명된 하위 리소스로 분기하지 않고 단일 PATCH로
 * 유지됨.
 *
 * 왜 ([DeviceRestaurantController]를 확장하지 않고) 형제 컨트롤러인가?
 * 그 컨트롤러는 명명된 하위 리소스의 부분 업데이트 모양을 위해 클래스
 * 수준 매핑을 `/api/devices/{deviceId}/restaurant`에 고정함. 이 라우트는
 * 하위 리소스가 *아님* — 디바이스 엔터티 자체를 대상으로 함. 스프링의
 * `@RequestMapping`은 한 컨트롤러 클래스가 여러 기본 경로로 깔끔하게
 * 분기하는 것을 허용하지 않으므로 각 라우트의 계약을 자기 완결적으로
 * 유지함.
 *
 * HTTP 계약:
 *  - 200 OK 성공 시 — 본문 = 패치 후 [DeviceResponse]
 *  - 400 Bad Request 요청 본문은 유효하지만 실행 가능한 필드가 없거나,
 *    개별 필드가 자체 검증에 실패할 때
 *    ([me.owldev.adsignage.web.GlobalExceptionHandler]에 위임)
 *  - 404 Not Found deviceId 또는 참조된 restaurantId가 알려지지 않은 경우
 *
 * 동작:
 *  - 요청 본문은 부분적: 호출자가 실제로 변경하려는 필드만 나타남. 키가
 *    없으면 해당 디바이스 필드는 변경되지 않음. 빈 문자열은 검증 시점에
 *    거부됨(클라이언트는 "변경 없음"을 의미할 때 `""`를 보내지 말고 키를
 *    생략해야 함).
 *  - [UpdateDeviceRequest.restaurantId]가 있으면 서비스는
 *    [DeviceAssignmentService.updateAssignment]에 위임하고, 이는 기존 활성
 *    행을 원자적으로 비활성화하고 새 행을 삽입한 다음 SSE 브리지가 소비하는
 *    `DeviceMappingChangedEvent`를 발행함 — 데모 시나리오 #3을 동작시키는
 *    동일한 와이어 흐름.
 *  - 응답은 항상 디바이스의 현재 활성 할당(있다면)을 에코하므로 호출자는
 *    후속 GET 없이 단일 왕복으로 패치 후 상태를 확인할 수 있음.
 *
 * 인가: 이 라우트는 해커톤 빌드를 위한 과도기 조치로 형제 `…/restaurant`
 * 및 `…/assignment` 예외와 함께 [SecurityConfig]에서 허용됨. `/api/devices`
 * 관리자 CRUD 접두사를 `ROLE_ADVERTISER` 뒤로 잠그는 auth-and-isolation
 * 패스가 이 라우트도 가져갈 것 — 컨트롤러 변경은 필요 없음.
 */
// TODO(hexagonal-cutover): assignment 컨텍스트 마이그레이션 시 이 컨트롤러를
//   bounded/context/device/adapter/in/api/ 로 옮긴다 (라우트는 device 의 PATCH
//   라이프사이클이라 device 컨텍스트 소속). 그 전까지는 새 DeviceController 와의
//   기본 빈 이름 충돌만 회피하기 위해 명시적 빈 이름 부여.
@RestController("legacyAssignmentDeviceController")
@RequestMapping("/api/devices/{deviceId}")
class DeviceController(
    private val deviceUpdateService: DeviceUpdateService,
) {

    private val log = LoggerFactory.getLogger(DeviceController::class.java)

    /**
     * 경로 id가 [deviceId]인 디바이스에 부분 업데이트 [body]를 적용.
     * 패치 후 [DeviceResponse]를 반환하며 해석된 현재 활성 할당을 포함하므로
     * 관리자 UI가 후속 GET 없이 결과를 렌더링할 수 있음.
     *
     * PUT이 아닌 PATCH 동사가 선택된 이유는 요청 본문이 가변 필드의 임의
     * 부분 집합을 운반 — 라우트는 설계상 부분적임.
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

        // 구문은 유효하지만 시맨틱이 비어 있는 본문을 미리 거부. 0필드
        // PATCH는 거의 확실히 클라이언트 버그(예: 어떤 입력도 없이 제출된
        // 관리자 폼). 변경되지 않은 현재 상태로 조용히 200을 반환하지 않고
        // 즉시 400으로 표면화함.
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
