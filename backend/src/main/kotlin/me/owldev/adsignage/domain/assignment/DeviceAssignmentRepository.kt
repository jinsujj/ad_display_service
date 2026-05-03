package me.owldev.adsignage.domain.assignment

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.util.Optional

/**
 * [DeviceAssignment]를 위한 Spring Data JPA repository.
 *
 * 디바이스의 "현재" 할당은 [DeviceAssignment.active]가 true인 행. 과거의
 * (비활성화된) 행들은 감사 목적으로 테이블에 남아 있음.
 *
 * 지원되는 핫패스:
 *  - 한 디바이스의 현재 할당 해석                            → [findByDeviceIdAndActiveTrue]
 *  - 여러 디바이스의 현재 할당을 일괄 나열                  → [findAllByActiveTrue] / [findAllByRestaurantIdAndActiveTrue]
 *  - 원자적 리매핑: 새 활성 행 삽입 전 디바이스의 현재
 *    할당을 비활성화                                          → [deactivateCurrentForDevice]
 */
@Repository
interface DeviceAssignmentRepository : JpaRepository<DeviceAssignment, String> {

    /**
     * [deviceId]의 현재(활성) 할당을 반환하며, 있는 경우에만. 디바이스는
     * 한 번에 최대 하나의 활성 할당을 가짐.
     */
    fun findByDeviceIdAndActiveTrue(deviceId: String): Optional<DeviceAssignment>

    /**
     * 현재 활성 모든 할당을 반환 — 관리자 UI가 전체 fleet의 디바이스 →
     * 음식점 맵을 렌더링해야 할 때 유용함.
     */
    fun findAllByActiveTrue(): List<DeviceAssignment>

    /**
     * 현재 [restaurantId]에 할당된 모든 디바이스를 반환 — 음식점 범위 뷰를
     * 구성하거나 단일 매장의 모든 디바이스로 플레이리스트 업데이트를 푸시할
     * 때 사용.
     */
    fun findAllByRestaurantIdAndActiveTrue(restaurantId: String): List<DeviceAssignment>

    /**
     * [deviceId]의 전체 할당 감사 추적을 최신 순으로 반환.
     */
    fun findAllByDeviceIdOrderByAssignedAtDesc(deviceId: String): List<DeviceAssignment>

    /**
     * [deviceId]에 대해 현재 활성 모든 행을 단일 UPDATE로 일괄 비활성화 —
     * 디바이스를 리매핑할 때 load-then-save 왕복을 회피.
     *
     * 영향받은 행의 수를 반환(디바이스가 활성 할당이 없으면 0, 일반적인
     * 경우 1).
     */
    @Modifying
    @Query(
        "UPDATE DeviceAssignment a SET a.active = false " +
            "WHERE a.deviceId = :deviceId AND a.active = true",
    )
    fun deactivateCurrentForDevice(@Param("deviceId") deviceId: String): Int

    /**
     * 어드민이 디바이스를 제거할 때 매핑 이력 전체(활성/비활성)를 일괄 삭제.
     */
    @Modifying
    @Query("DELETE FROM DeviceAssignment a WHERE a.deviceId = :deviceId")
    fun deleteAllByDeviceId(@Param("deviceId") deviceId: String): Int
}
