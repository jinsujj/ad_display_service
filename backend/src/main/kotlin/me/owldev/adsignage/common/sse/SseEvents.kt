package me.owldev.adsignage.common.sse

import java.time.Instant

/**
 * 플레이어 페이지로 전송되는 SSE 이벤트의 와이어 포맷 상수와 페이로드 DTO.
 *
 * 와이어 계약은 컨텍스트가 아닌 *프런트엔드 - 백엔드 합의* 이므로 common 에
 * 모은다. 각 도메인 이벤트(`PlaylistUpdatedEvent`, `DeviceMappingChangedEvent`)
 * 와 SSE 리스너/퍼블리셔는 자기 컨텍스트의 `adapter/out/sse/` 에 위치하지만,
 * 와이어 이름과 페이로드 형태는 여기서 정의해 한 곳에서 변경 추적이 가능.
 */
object SseEventNames {
    /** 플레이어가 연결된 직후 한 번 전송. */
    const val CONNECTED = "CONNECTED"

    /** 관리자가 디바이스를 다른 음식점으로 리매핑할 때 전송. */
    const val MAPPING_CHANGED = "MAPPING_CHANGED"

    /** 디바이스의 플레이리스트가 변경되었을 때 전송. */
    const val PLAYLIST_UPDATE = "PLAYLIST_UPDATE"
}

/**
 * [SseEventNames.CONNECTED] 의 페이로드.
 */
data class ConnectedPayload(
    val deviceId: String,
    val serverTime: Instant = Instant.now(),
)

/**
 * [SseEventNames.MAPPING_CHANGED] 의 페이로드.
 */
data class MappingChangedPayload(
    val deviceId: String,
    val restaurantId: String,
    val assignmentId: String,
    val assignedAt: Instant,
)

/**
 * [SseEventNames.PLAYLIST_UPDATE] 의 페이로드 (sub-AC 50003.3).
 *
 * `web/hooks/usePlayerSse.ts` 의 TypeScript 와이어 계약 `PlaylistUpdatePayload`
 * 를 미러링.
 */
data class PlaylistUpdatedPayload(
    val deviceId: String,
    val restaurantId: String? = null,
    val updatedAt: Instant? = Instant.now(),
    val playlist: Any? = null,
)
