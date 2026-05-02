package me.owldev.adsignage.sse

import java.time.Instant

/**
 * 디바이스의 음식점 할당이 변경되었음을 알리는 애플리케이션 이벤트.
 *
 * `DeviceAssignmentService`가 새로운 활성 할당 행을 기록하는 `@Transactional`
 * 메서드 내부에서 발행. [DeviceMappingChangedSseListener]가 이를 소비하여
 * [deviceId]에 대해 연결된 모든 SSE emitter로 이벤트를 팬아웃.
 *
 * 왜 (서비스에서의 직접 registry 호출 대신) 애플리케이션 이벤트인가:
 *  - 도메인 서비스가 HTTP / SSE 관심사로부터 자유로워짐 — 웹 레이어를
 *    부팅하지 않고도 서비스를 단위 테스트 할 수 있음.
 *  - 형제 기능(감사 로그, 푸시 알림, 메트릭)이 서비스를 수정하지 않고도
 *    훅(hook)을 걸 수 있음.
 *  - DB 트랜잭션이 커밋된 **이후에만** 브로드캐스트가 발생하도록 함.
 *    리스너는 `@TransactionalEventListener(phase = AFTER_COMMIT)`로
 *    연결되어 있어, 스프링은 트랜잭션이 커밋될 때까지 호출을 지연시킴.
 *    이로써 다음이 보장됨:
 *      1. 트랜잭션이 롤백되면 SSE 이벤트가 전송되지 않음 — 플레이어가
 *         영속화된 상태와 모순되는 가짜 리매핑을 보지 않음.
 *      2. 플레이어가 MAPPING_CHANGED를 받고 플레이리스트를 재조회할 때,
 *         새 할당 행이 이미 커밋되어 읽기에서 보임.
 */
data class DeviceMappingChangedEvent(
    val deviceId: String,
    val restaurantId: String,
    val assignmentId: String,
    val assignedAt: Instant,
)
