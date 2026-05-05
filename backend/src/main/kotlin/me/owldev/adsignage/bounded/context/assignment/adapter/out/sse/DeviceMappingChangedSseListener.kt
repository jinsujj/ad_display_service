package me.owldev.adsignage.bounded.context.assignment.adapter.out.sse

import me.owldev.adsignage.bounded.context.device.adapter.`in`.sse.DeviceSseRegistry
import me.owldev.adsignage.common.sse.MappingChangedPayload
import me.owldev.adsignage.common.sse.SseEventNames
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

/**
 * [DeviceMappingChangedEvent] 를 SSE 와이어로 브리지하여, 영향받는 디바이스에
 * 현재 등록된 모든 emitter 로 MAPPING_CHANGED 이벤트를 브로드캐스트.
 *
 * 데모 시나리오 #3(실시간 device-to-restaurant 리매핑) 의 listener 절반:
 *  1. 관리자가 `PUT /api/devices/{id}/assignment` 호출
 *  2. DeviceAssignmentService 가 새 활성 행 + [DeviceMappingChangedEvent] 발행
 *  3. 트랜잭션 커밋 후 이 리스너 실행 (AFTER_COMMIT)
 *  4. device 컨텍스트의 SSE registry 를 통해 디바이스에 MAPPING_CHANGED 푸시
 *  5. 안드로이드 플레이어가 새 음식점에 대한 플레이리스트 재조회
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
