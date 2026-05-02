package me.owldev.adsignage.domain.assignment

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/**
 * Existence checks against the parent tables referenced by [DeviceAssignment].
 *
 * Sub-AC 2 note: the Device and Restaurant entities/repositories are owned by
 * sibling sub-ACs and may not yet exist as JPA classes when this code is built.
 * To keep this sub-AC self-contained while still validating that referenced
 * device_id / restaurant_id values exist, we query the parent tables directly
 * via [JdbcTemplate] (the tables themselves are guaranteed by the V90 FK).
 *
 * When the Device/Restaurant entity layers land, callers can swap in a richer
 * implementation without touching [DeviceAssignmentService].
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
