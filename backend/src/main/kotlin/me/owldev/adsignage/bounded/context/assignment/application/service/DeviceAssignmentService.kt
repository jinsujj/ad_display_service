package me.owldev.adsignage.bounded.context.assignment.application.service

import me.owldev.adsignage.bounded.context.assignment.application.port.out.database.DeviceAssignmentRepositoryPort
import me.owldev.adsignage.bounded.context.assignment.application.port.out.database.DeviceLookupPort
import me.owldev.adsignage.bounded.context.assignment.application.port.out.database.RestaurantLookupPort
import me.owldev.adsignage.bounded.context.assignment.domain.exception.AssignmentNotFoundException
import me.owldev.adsignage.bounded.context.assignment.domain.exception.DeviceNotFoundException
import me.owldev.adsignage.bounded.context.assignment.domain.exception.RestaurantNotFoundException
import me.owldev.adsignage.bounded.context.assignment.domain.model.DeviceAssignment
import me.owldev.adsignage.bounded.context.assignment.adapter.out.sse.DeviceMappingChangedEvent
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 디바이스 → 음식점 할당 라이프사이클을 소유하는 서비스.
 *
 *  - [createAssignment] — 디바이스에 대해 첫 번째/유일한 활성 할당을 생성.
 *  - [updateAssignment] — 디바이스를 다른 음식점으로 원자적으로 리매핑.
 *  - [getCurrentAssignment] — 현재 활성 할당을 가져옴.
 *  - 쓰기 전에 참조된 device_id / restaurant_id가 존재하는지 검증.
 *
 * 동시성 노트: [updateAssignment]와 [createAssignment]는 단일 트랜잭션에서
 * 실행되어 "이전 비활성화 + 새로 삽입" 쌍이 원자적임 — 디바이스가 0개 또는
 * 2개의 활성 행을 갖는 순간은 절대 없음.
 *
 * SSE 노트 (sub-AC 50102.2): 모든 성공적인 생성/업데이트에서 서비스는
 * [DeviceMappingChangedEvent]를 발행하여 SSE 브리지 레이어가 해당 디바이스에
 * 연결된 모든 플레이어로 MAPPING_CHANGED 이벤트를 푸시할 수 있게 함.
 * 도메인 서비스는 HTTP / SSE 관심사에서 자유롭게 유지됨 — 단지 "이 매핑이
 * 변경되었다"고 알릴 뿐이고, [me.owldev.adsignage.sse] 모듈이 클라이언트에
 * 어떻게 전달될지를 결정함. 전송은 **DB 업데이트가 커밋된 이후에**
 * 발생함 — 리스너는 `@TransactionalEventListener(AFTER_COMMIT)`로 연결되어
 * 있어, 롤백된 트랜잭션은 와이어에 가짜 리매핑 이벤트를 절대 생성하지 않음.
 */
@Service
class DeviceAssignmentService(
    private val assignmentRepositoryPort: DeviceAssignmentRepositoryPort,
    private val deviceLookupPort: DeviceLookupPort,
    private val restaurantLookupPort: RestaurantLookupPort,
    private val eventPublisher: ApplicationEventPublisher,
) {

    private val log = LoggerFactory.getLogger(DeviceAssignmentService::class.java)

    /**
     * [deviceId] → [restaurantId]에 대해 새 활성 할당을 생성.
     *
     * 디바이스가 이미 활성 할당을 가지고 있으면 [updateAssignment](비활성화 후
     * 삽입)에 위임하여 호출자가 이 메서드를 멱등적인 "현재 할당 설정" 진입점으로
     * 사용할 수 있게 함.
     */
    @Transactional
    fun createAssignment(deviceId: String, restaurantId: String): DeviceAssignment {
        validateReferencesExist(deviceId, restaurantId)

        val existing = assignmentRepositoryPort.findByDeviceIdAndActiveTrue(deviceId)
        if (existing != null) {
            log.info(
                "createAssignment: device {} already has active assignment {} → delegating to updateAssignment",
                deviceId,
                existing.id,
            )
            return updateAssignmentInternal(deviceId, restaurantId)
        }

        val saved = assignmentRepositoryPort.save(
            DeviceAssignment(deviceId = deviceId, restaurantId = restaurantId),
        )
        log.info(
            "createAssignment: device={} restaurant={} assignmentId={}",
            deviceId, restaurantId, saved.id,
        )
        publishMappingChanged(saved)
        return saved
    }

    /**
     * [deviceId]를 [newRestaurantId]로 리매핑. 같은 트랜잭션에서 기존 활성
     * 행(있다면)을 비활성화하고 새 활성 행을 삽입.
     */
    @Transactional
    fun updateAssignment(deviceId: String, newRestaurantId: String): DeviceAssignment {
        validateReferencesExist(deviceId, newRestaurantId)
        return updateAssignmentInternal(deviceId, newRestaurantId)
    }

    /**
     * [deviceId]에 대한 현재 활성 할당을 반환.
     */
    @Transactional(readOnly = true)
    fun getCurrentAssignment(deviceId: String): DeviceAssignment =
        assignmentRepositoryPort.findByDeviceIdAndActiveTrue(deviceId)
            ?: throw AssignmentNotFoundException(deviceId)

    /**
     * [deviceId]에 대한 현재 활성 할당을 반환하거나, 디바이스가 현재
     * 할당되지 않았다면 `null`을 반환.
     */
    @Transactional(readOnly = true)
    fun findCurrentAssignment(deviceId: String): DeviceAssignment? =
        assignmentRepositoryPort.findByDeviceIdAndActiveTrue(deviceId)

    private fun validateReferencesExist(deviceId: String, restaurantId: String) {
        require(deviceId.isNotBlank()) { "deviceId must not be blank" }
        require(restaurantId.isNotBlank()) { "restaurantId must not be blank" }
        if (!deviceLookupPort.exists(deviceId)) throw DeviceNotFoundException(deviceId)
        if (!restaurantLookupPort.exists(restaurantId)) throw RestaurantNotFoundException(restaurantId)
    }

    /**
     * 원자적인 비활성화 후 삽입. 호출자는 [deviceId]와 [newRestaurantId]가
     * 존재함을 이미 검증한 책임을 짐.
     */
    private fun updateAssignmentInternal(deviceId: String, newRestaurantId: String): DeviceAssignment {
        val deactivated = assignmentRepositoryPort.deactivateCurrentForDevice(deviceId)
        val saved = assignmentRepositoryPort.save(
            DeviceAssignment(deviceId = deviceId, restaurantId = newRestaurantId),
        )
        log.info(
            "updateAssignment: device={} → restaurant={} (deactivatedRows={}, newAssignmentId={})",
            deviceId, newRestaurantId, deactivated, saved.id,
        )
        publishMappingChanged(saved)
        return saved
    }

    /**
     * SSE 브리지가 수신하는 [DeviceMappingChangedEvent]를 발행. 리스너는
     * AFTER_COMMIT에 바인딩되어 있어 SSE 브로드캐스트는 할당 행이 데이터베이스에
     * 영구적으로 커밋된 이후에만 실행됨.
     */
    private fun publishMappingChanged(saved: DeviceAssignment) {
        try {
            eventPublisher.publishEvent(
                DeviceMappingChangedEvent(
                    deviceId = saved.deviceId,
                    restaurantId = saved.restaurantId,
                    assignmentId = saved.id,
                    assignedAt = saved.assignedAt,
                ),
            )
        } catch (ex: Exception) {
            log.warn(
                "Failed to publish DeviceMappingChangedEvent for device={}: {}",
                saved.deviceId, ex.message,
            )
        }
    }
}
