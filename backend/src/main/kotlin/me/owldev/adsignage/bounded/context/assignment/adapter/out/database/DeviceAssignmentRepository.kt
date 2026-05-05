package me.owldev.adsignage.bounded.context.assignment.adapter.out.database

import me.owldev.adsignage.bounded.context.assignment.domain.model.DeviceAssignment
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
 */
@Repository
interface DeviceAssignmentRepository : JpaRepository<DeviceAssignment, String> {

    fun findByDeviceIdAndActiveTrue(deviceId: String): Optional<DeviceAssignment>

    fun findAllByActiveTrue(): List<DeviceAssignment>

    fun findAllByRestaurantIdAndActiveTrue(restaurantId: String): List<DeviceAssignment>

    fun findAllByDeviceIdOrderByAssignedAtDesc(deviceId: String): List<DeviceAssignment>

    @Modifying
    @Query(
        "UPDATE DeviceAssignment a SET a.active = false " +
            "WHERE a.deviceId = :deviceId AND a.active = true",
    )
    fun deactivateCurrentForDevice(@Param("deviceId") deviceId: String): Int

    @Modifying
    @Query("DELETE FROM DeviceAssignment a WHERE a.deviceId = :deviceId")
    fun deleteAllByDeviceId(@Param("deviceId") deviceId: String): Int
}
