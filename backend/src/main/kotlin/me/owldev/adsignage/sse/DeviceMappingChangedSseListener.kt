package me.owldev.adsignage.sse

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

/**
 * [DeviceMappingChangedEvent]를 SSE 와이어로 브리지하여, 영향받는 디바이스에
 * 현재 등록된 모든 emitter로 [SseEventNames.MAPPING_CHANGED] 이벤트를 브로드캐스트.
 *
 * 데모 시나리오 #3(실시간 device-to-restaurant 리매핑)을 동작시키는
 * publisher/listener 쌍의 listener 절반:
 *
 *  1. 관리자가 새로운 음식점으로 `PUT /api/devices/{id}/assignment` 호출.
 *  2. `DeviceAssignmentService.updateAssignment`가 새 활성 행을 쓰고
 *     트랜잭션 내부에서 [DeviceMappingChangedEvent]를 발행.
 *  3. 트랜잭션이 커밋됨 — 행이 영속화된 **이후에만** 스프링이 이 리스너를
 *     호출(이 리스너는 [TransactionalEventListener]의 phase
 *     [TransactionPhase.AFTER_COMMIT]로 연결됨).
 *  4. 이 리스너가 영향받는 deviceId에 대해 [DeviceSseRegistry.broadcast]를
 *     호출.
 *  5. 안드로이드 플레이어 페이지(라우트 `/player/{deviceId}`)는 장기 SSE
 *     구독을 유지하다가 이벤트를 받고 새 음식점에 대한 플레이리스트를
 *     재조회.
 *
 * # 왜 동기 `@EventListener`가 아니라 AFTER_COMMIT인가
 *  - **DB와의 일관성**: 할당 트랜잭션이 롤백되면(예: 플러시 시점에 제약
 *    위반이 발생) MAPPING_CHANGED 이벤트가 전송되지 않으므로, 플레이어가
 *    영속화된 상태와 일치하지 않는 가짜 리매핑을 보지 않음.
 *  - **재조회 순서**: 플레이어가 MAPPING_CHANGED를 받고 즉시
 *    `GET /api/playlist`를 가져갈 때, 할당 행은 이미 커밋되어 플레이리스트
 *    쿼리에서 보임 — 따라서 재조회는 이전 음식점이 아닌 새 음식점의
 *    플레이리스트를 반환.
 *  - **리스너별 실패 격리 안전**: emitter별 SSE 전송 실패는 리스너가
 *    post-commit에서 동작하므로 할당을 롤백시킬 수 없음.
 *
 * # `fallbackExecution = true`
 * 이벤트가 트랜잭션 *밖*에서 발행되는 경우(예: `@Transactional` 없이
 * 서비스를 호출하는 테스트, 또는 트랜잭션 서비스를 우회하는 향후 임시
 * 관리 도구), 스프링은 바인딩할 트랜잭션 phase가 없기 때문에 보통 이벤트를
 * 조용히 드롭함. `fallbackExecution = true`로 설정하면 스프링이 이런
 * 경우에도 즉시 리스너를 호출하므로 브로드캐스트가 여전히 발생함.
 * 통합 테스트는 이 동작에 의존함.
 */
@Component
class DeviceMappingChangedSseListener(
    private val registry: DeviceSseRegistry,
) {

    private val log = LoggerFactory.getLogger(DeviceMappingChangedSseListener::class.java)

    @TransactionalEventListener(
        phase = TransactionPhase.AFTER_COMMIT,
        fallbackExecution = true,
    )
    fun onMappingChanged(event: DeviceMappingChangedEvent) {
        val payload = MappingChangedPayload(
            deviceId = event.deviceId,
            restaurantId = event.restaurantId,
            assignmentId = event.assignmentId,
            assignedAt = event.assignedAt,
        )
        val sseEvent = SseEmitter.event()
            .name(SseEventNames.MAPPING_CHANGED)
            .data(payload)
            .id(event.assignmentId)

        val delivered = registry.broadcast(event.deviceId, sseEvent)
        log.info(
            "MAPPING_CHANGED → device={} newRestaurant={} delivered={}",
            event.deviceId,
            event.restaurantId,
            delivered,
        )
    }
}
