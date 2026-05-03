package me.owldev.adsignage.domain.assignment.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import me.owldev.adsignage.domain.assignment.DeviceAssignment
import java.time.Instant

/**
 * `POST /api/devices/{id}/assignment` 의 요청 본문.
 *
 * 아직 현재 매핑이 없는 디바이스(또는 기존 활성 매핑을 교체할 디바이스 —
 * 서비스 계층은 생성 동작을 멱등하게 "현재 매핑 설정"으로 다룬다)에 대한
 * 대상 음식점을 전달한다.
 *
 * 온톨로지 매핑:
 *  - [restaurantId] → device_restaurant_id (FK → restaurants.restaurant_id)
 */
data class CreateAssignmentRequest(
    @field:NotBlank(message = "restaurantId must not be blank")
    @field:Size(max = 36, message = "restaurantId must be at most 36 characters")
    val restaurantId: String,
)

/**
 * `PUT /api/devices/{id}/assignment` 의 요청 본문.
 *
 * 디바이스에 새로운 대상 음식점을 전달한다. 서비스 계층은 단일 트랜잭션에서
 * 이전 활성 행(있다면)을 비활성화하고 새 활성 행을 삽입한다 — 이 엔드포인트가
 * 데모 시나리오 #3의 SSE 기반 재할당 진입점이다.
 *
 * 현재는 [CreateAssignmentRequest]와 형태가 같지만, PUT 전용 필드(예: 낙관적
 * 동시성을 위한 `expectedAssignmentId`)를 추후 추가해도 POST 호출자가
 * 깨지지 않도록 별도 타입으로 분리해 두었다.
 */
data class UpdateAssignmentRequest(
    @field:NotBlank(message = "restaurantId must not be blank")
    @field:Size(max = 36, message = "restaurantId must be at most 36 characters")
    val restaurantId: String,
)

/**
 * `PATCH /api/devices/{deviceId}/restaurant` (Sub-AC 50101.1) 요청 본문.
 *
 * 부분 업데이트 동사로 디바이스의 새 대상 음식점을 전달한다. 의미적으로는
 * [UpdateAssignmentRequest]와 동일하며 — 둘 다 결국 같은 서비스 계층의
 * 원자적 deactivate-then-insert 를 호출 — 다만 (`PATCH …/restaurant`)
 * 라우트+동사 조합이 AC 계약이 요구하는 형태다: 명명된 하위 리소스에
 * PATCH를 거는 형태는 "디바이스의 `restaurant` 연관을 수정"으로 읽히고,
 * 어드민 UI의 가벼운 재할당 폼이 타깃하는 엔드포인트다.
 *
 * 라우트별 검증/필드(예: 낙관적 동시성을 위한 `expectedRestaurantId`,
 * 또는 detach 의미의 `null`)를 추후 추가해도 기존 `PUT …/assignment`
 * 호출자가 깨지지 않도록 [UpdateAssignmentRequest]를 재사용하지 않고
 * 별도 클래스로 두었다.
 *
 * 온톨로지 매핑:
 *  - [restaurantId] → device_restaurant_id (FK → restaurants.restaurant_id)
 */
data class UpdateDeviceRestaurantRequest(
    @field:NotBlank(message = "restaurantId must not be blank")
    @field:Size(max = 36, message = "restaurantId must be at most 36 characters")
    val restaurantId: String,
)

/**
 * `PATCH /api/devices/{deviceId}` (AC 9, Sub-AC 1) 요청 본문.
 *
 * 디바이스 레코드에 대한 일반 부분 업데이트 진입점. 모든 필드는 **선택적** —
 * 호출자는 변경하고 싶은 필드만 보내며, 동사-라우트 조합이 PATCH 시맨틱에
 * 충실하도록 한다. JSON 본문에 키가 존재한다는 것 자체가 업데이트를
 * 트리거하고, 키가 없으면 해당 디바이스 필드는 손대지 않는다.
 *
 * 본 sub-AC가 지원하는 필드:
 *  - [restaurantId] — 디바이스의 활성 음식점 매핑을 재할당. 존재하면
 *    서비스가 기존
 *    [me.owldev.adsignage.domain.assignment.DeviceAssignmentService.updateAssignment]
 *    경로(원자적 deactivate-then-insert + SSE MAPPING_CHANGED publish)에 위임.
 *  - [screenName]   — 디바이스가 거치된 물리 화면을 가리키는 자유 형식
 *    디스플레이 라벨. 어드민 UI의 그루핑 용도(예: "Lobby TV", "Counter
 *    Display")에 유용. 매핑이 아닌 디바이스 레코드 자체에 저장되어 음식점
 *    재할당에도 살아남는다.
 *  - [groupName]    — 어드민 UI가 일괄 동작을 위해 디바이스를 묶을 수 있는
 *    자유 형식 그룹 라벨(예: "north-store-fleet").
 *
 * `screenName` / `groupName` 관련: V10 `devices` 테이블은 형제 sub-AC가
 * 소유하며 아직 이 컬럼들을 가지고 있지 않을 수 있다. 컨트롤러+서비스는
 * 와이어 계약을 미래 호환되게 유지하기 위해 키를 받아주며, DB 측 컬럼이
 * 누락되면 JDBC 500이 아닌 타입화된
 * [me.owldev.adsignage.domain.assignment.DeviceFieldUnsupportedException]
 * (HTTP 422)로 노출된다. 데모 빌드의 호출자는 보통 `restaurantId`만 보낸다.
 *
 * 검증: 존재하는 모든 필드를 독립적으로 검증한다. 빈 문자열 값은 거절
 * (필드 생략으로 "변경 없음"을 의미)되어 계약상 "빈 값으로 설정"과
 * "그대로 두기"가 구분된다 — 현재는 후자만 지원한다.
 */
data class UpdateDeviceRequest(
    @field:Size(min = 1, max = 36, message = "restaurantId must be 1..36 characters when present")
    val restaurantId: String? = null,

    @field:Size(min = 1, max = 128, message = "screenName must be 1..128 characters when present")
    val screenName: String? = null,

    @field:Size(min = 1, max = 128, message = "groupName must be 1..128 characters when present")
    val groupName: String? = null,

    /**
     * 디바이스 별칭 — 어드민 UI 가 자동 생성된 모델명/시리얼 대신 운영 친화적
     * 라벨 ("강남 1호점", "홍대 카운터") 로 식별할 수 있게 해 준다. 빈 문자열은
     * "그대로 두기" 와 "지우기" 모호성을 피하기 위해 거절 — 1자 이상.
     */
    @field:Size(min = 1, max = 255, message = "deviceName must be 1..255 characters when present")
    val deviceName: String? = null,
) {
    /**
     * 요청 본문이 서비스가 적용 방법을 아는 필드를 하나도 담지 않은 경우
     * `true` — 컨트롤러가 무동작 PATCH를 현재 상태를 조용히 반환하는 대신
     * 400으로 단락하기 위해 사용한다.
     */
    fun isEmpty(): Boolean =
        restaurantId == null && screenName == null && groupName == null && deviceName == null
}

/**
 * `PATCH /api/devices/{deviceId}` (AC 9, Sub-AC 1) 응답 본문.
 *
 * 패치 후 디바이스의 뷰를 반환한다 — 결정된 활성 매핑 + 같은 요청에서
 * 업데이트된 디바이스 레벨 필드. 의도적으로 [AssignmentResponse]의
 * 상위 집합 형태라, 매핑 페이로드를 이미 소비하는 어드민 UI 코드가
 * 파서 재작성 없이 일반 PATCH 엔드포인트로 전환할 수 있다.
 *
 * 패치 후 디바이스에 활성 매핑이 없으면(예: 미래 sub-AC가 "unassign"
 * 경로 추가) `restaurantId`는 `null`일 수 있다. 현재 sub-AC에서는
 * `restaurantId`를 PATCH한 호출자는 항상 여기서 메아리를 본다.
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
 * `POST` 와 `PUT /api/devices/{id}/assignment`,
 * 그리고 `PATCH /api/devices/{deviceId}/restaurant` (Sub-AC 50101.1)의 응답 본문.
 *
 * 영속화된 [DeviceAssignment]를 안정적인 와이어 계약으로 미러링한다 —
 * Hibernate 관리 상태를 컨트롤러 경계 밖으로 두고, JSON 필드 집합이
 * 엔터티 컬럼 레이아웃과 독립적으로 진화하도록 한다.
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
