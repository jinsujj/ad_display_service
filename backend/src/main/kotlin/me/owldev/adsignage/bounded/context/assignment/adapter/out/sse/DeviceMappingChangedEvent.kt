package me.owldev.adsignage.bounded.context.assignment.adapter.out.sse

import java.time.Instant

/**
 * 디바이스의 음식점 할당이 변경되었음을 알리는 애플리케이션 이벤트.
 *
 * `DeviceAssignmentService` 가 새로운 활성 할당 행을 기록하는 `@Transactional`
 * 메서드 내부에서 발행. [DeviceMappingChangedSseListener] 가 이를 소비하여
 * [deviceId] 에 대해 연결된 모든 SSE emitter 로 이벤트를 팬아웃.
 *
 * 도메인 서비스가 HTTP/SSE 관심사로부터 자유로워지고, 트랜잭션이 커밋된
 * 이후에만(AFTER_COMMIT) 브로드캐스트 — 가짜 리매핑을 방지.
 */
data class DeviceMappingChangedEvent(
    val deviceId: String,
    val restaurantId: String,
    val assignmentId: String,
    val assignedAt: Instant,
)
