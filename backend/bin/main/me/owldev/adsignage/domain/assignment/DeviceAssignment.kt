package me.owldev.adsignage.domain.assignment

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * DeviceAssignment는 시점 t에 물리 사이니지 디바이스를 음식점에 매핑.
 *
 * Flyway 마이그레이션 V90이 생성한 device_assignments 테이블에 매핑.
 *
 * [active] 플래그는 디바이스의 현재/유효한 할당을 식별. 디바이스가
 * 리매핑되면(SSE 기반 데모 시나리오 #3), 기존 활성 행이 비활성화되고
 * 새 활성 행이 삽입되므로 테이블은 과거 매핑의 감사 로그 역할도 함.
 *
 * 표현된 온톨로지 개념:
 *  - device_id              → [deviceId]    (FK → devices.device_id)
 *  - device_restaurant_id   → [restaurantId] (FK → restaurants.restaurant_id)
 *  - assigned_at            → [assignedAt]
 *  - (active 플래그)        → [active]
 */
@Entity
@Table(
    name = "device_assignments",
    indexes = [
        Index(name = "idx_device_assignments_device_id", columnList = "device_id"),
        Index(name = "idx_device_assignments_restaurant_id", columnList = "restaurant_id"),
        Index(name = "idx_device_assignments_active", columnList = "active"),
        Index(name = "idx_device_assignments_device_active", columnList = "device_id, active"),
    ],
)
class DeviceAssignment(
    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    val id: String = UUID.randomUUID().toString(),

    @Column(name = "device_id", nullable = false, length = 36)
    val deviceId: String,

    @Column(name = "restaurant_id", nullable = false, length = 36)
    val restaurantId: String,

    @Column(name = "assigned_at", nullable = false, updatable = false, columnDefinition = "TIMESTAMP")
    val assignedAt: Instant = Instant.now(),

    @Column(name = "active", nullable = false)
    var active: Boolean = true,
) {
    /**
     * 이 할당을 더 이상 현재가 아니라고 표시. 디바이스가 리매핑될 때 사용:
     * 이전 활성 행은 비활성화되고 새 활성 행이 삽입됨.
     */
    fun deactivate() {
        this.active = false
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DeviceAssignment) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}
