package me.owldev.adsignage.bounded.context.device.domain.dto

import jakarta.validation.constraints.Size
import me.owldev.adsignage.bounded.context.assignment.domain.model.DeviceAssignment
import java.time.Instant

/**
 * `PATCH /api/devices/{deviceId}` (AC 9, Sub-AC 1) 요청 본문.
 *
 * 디바이스 레코드에 대한 일반 부분 업데이트 진입점. 모든 필드는 **선택적** —
 * 호출자는 변경하고 싶은 필드만 보내며, 동사-라우트 조합이 PATCH 시맨틱에
 * 충실하도록 한다. JSON 본문에 키가 존재한다는 것 자체가 업데이트를
 * 트리거하고, 키가 없으면 해당 디바이스 필드는 손대지 않는다.
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
 * `PATCH /api/devices/{deviceId}` 응답 본문. 패치 후 디바이스 뷰 — 결정된
 * 활성 매핑 + 같은 요청에서 업데이트된 디바이스 레벨 필드.
 */
data class UpdateDeviceResponse(
    val deviceId: String,
    val restaurantId: String?,
    val assignmentId: String?,
    val assignedAt: Instant?,
    val screenName: String? = null,
    val groupName: String? = null,
) {
    companion object {
        fun fromAssignment(deviceId: String, entity: DeviceAssignment?): UpdateDeviceResponse =
            UpdateDeviceResponse(
                deviceId = deviceId,
                restaurantId = entity?.restaurantId,
                assignmentId = entity?.id,
                assignedAt = entity?.assignedAt,
            )
    }
}
