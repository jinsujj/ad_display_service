package me.owldev.adsignage.domain.assignment

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/**
 * [DeviceAssignment]가 참조하는 부모 테이블에 대한 존재 검사.
 *
 * Sub-AC 2 노트: Device와 Restaurant 엔터티/repository는 형제 sub-AC가
 * 소유하며 이 코드가 빌드될 때 JPA 클래스로 아직 존재하지 않을 수 있음.
 * 이 sub-AC를 자기 완결적으로 유지하면서도 참조된 device_id / restaurant_id
 * 값이 존재하는지 검증하기 위해 부모 테이블을 [JdbcTemplate]을 통해 직접
 * 쿼리(테이블 자체는 V90 FK에 의해 보장됨).
 *
 * Device/Restaurant 엔터티 레이어가 도착하면 호출자는 [DeviceAssignmentService]를
 * 건드리지 않고 더 풍부한 구현으로 교체할 수 있음.
 */
interface DeviceLookup {
    fun exists(deviceId: String): Boolean
}

interface RestaurantLookup {
    fun exists(restaurantId: String): Boolean
}

@Component
class JdbcDeviceLookup(private val jdbc: JdbcTemplate) : DeviceLookup {
    override fun exists(deviceId: String): Boolean {
        val count: Int? = jdbc.queryForObject(
            "SELECT COUNT(*) FROM devices WHERE device_id = ?",
            Int::class.java,
            deviceId,
        )
        return (count ?: 0) > 0
    }
}

@Component
class JdbcRestaurantLookup(private val jdbc: JdbcTemplate) : RestaurantLookup {
    override fun exists(restaurantId: String): Boolean {
        val count: Int? = jdbc.queryForObject(
            "SELECT COUNT(*) FROM restaurants WHERE restaurant_id = ?",
            Int::class.java,
            restaurantId,
        )
        return (count ?: 0) > 0
    }
}
