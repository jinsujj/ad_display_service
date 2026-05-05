package me.owldev.adsignage.bounded.context.device.domain.dto

import com.fasterxml.jackson.annotation.JsonInclude
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant

data class RegisterDeviceRequest(
    @field:NotBlank(message = "deviceId must not be blank")
    @field:Size(max = 36, message = "deviceId must be at most 36 characters")
    val deviceId: String?,

    @field:Size(max = 255, message = "deviceName must be at most 255 characters")
    val deviceName: String? = null,
)

data class RegisterDeviceResponse(
    val deviceId: String,
    val deviceName: String,
    val registeredAt: Instant,
    val lastSeenAt: Instant?,
)

@JsonInclude(JsonInclude.Include.ALWAYS)
data class DeviceListItem(
    val deviceId: String,
    val deviceName: String,
    val registeredAt: Instant,
    /** 마지막으로 디바이스 활동(register / play-event) 이 관측된 시각. 모니터링
     *  카드의 "마지막 활동 N분 전" 라벨이 사용. null 이면 아직 한 번도 안 본 것. */
    val lastSeenAt: Instant?,
    val currentRestaurant: CurrentRestaurantDto?,
    val queuedAds: List<QueuedAdSummary> = emptyList(),
    /**
     * 디바이스가 지금 살아 있는가? SSE 연결 + 최근 90초 내 play-event 두 신호의
     * OR. 어드민 모니터링 카드가 회색/컬러를 결정.
     */
    val online: Boolean = false,
    /**
     * 디바이스가 *현재 송출 중* 인 광고. STARTED 이벤트 기반이므로 서버
     * 진실이며, 클라이언트가 큐를 시뮬레이션한 결과가 아니다. 오프라인이거나
     * 90초 내 STARTED 가 없으면 null.
     */
    val currentAd: CurrentAdDto? = null,
)

/**
 * 어드민 디바이스 탭의 모니터링 썸네일용 가벼운 광고 요약. 풀 광고 메타가
 * 필요하면 디바이스 상세에서 `GET /api/devices/{id}/ads` 사용.
 */
data class QueuedAdSummary(
    val adId: String,
    val title: String,
    val videoFilename: String,
    /** SCHEDULED / ACTIVE / EXPIRED — Ad.computeStatus() 결과. */
    val status: String,
)

/**
 * "지금 디바이스가 송출 중인 광고" 의 최소 메타. 모니터 카드에 동일한 영상을
 * autoplay 로 미러링하는 데 필요한 만큼만 운반.
 */
data class CurrentAdDto(
    val adId: String,
    val title: String,
    val videoFilename: String,
    /** 디바이스가 광고를 시작했다고 보고한 시각(occurredAt). 운영자 UI 가
     *  "▶ 0:24 째 송출 중" 같은 경과 시간 라벨에 사용 가능. */
    val startedAt: Instant,
)

data class CurrentRestaurantDto(
    val restaurantId: String,
    val restaurantName: String,
    val address: String?,
    val assignedAt: Instant,
)

data class DeviceDetailResponse(
    val deviceId: String,
    val deviceName: String,
    val registeredAt: Instant,
    val lastSeenAt: Instant?,
    val currentAssignment: AssignmentHistoryItem?,
    val history: List<AssignmentHistoryItem>,
)

data class AssignmentHistoryItem(
    val assignmentId: String,
    val restaurantId: String,
    val restaurantName: String,
    val address: String?,
    val assignedAt: Instant,
    val active: Boolean,
)
