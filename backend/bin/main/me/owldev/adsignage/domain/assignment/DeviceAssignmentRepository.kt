package me.owldev.adsignage.domain.assignment

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.util.Optional

/**
 * Spring Data JPA repository for [DeviceAssignment].
 *
 * The "current" assignment for a device is the row where [DeviceAssignment.active] is true.
 * Historic (deactivated) rows remain in the table for audit purposes.
 *
 * Hot paths supported:
 *  - Resolve the current assignment for one device          → [findByDeviceIdAndActiveTrue]
 *  - List the current assignments for many devices in bulk  → [findAllByActiveTrue] / [findAllByRestaurantIdAndActiveTrue]
 *  - Atomic remap: deactivate any current assignment for a
 *    device before inserting a new active row                → [deactivateCurrentForDevice]
 */
@Repository
interface DeviceAssignmentRepository : JpaRepository<DeviceAssignment, String> {

    /**
     * Returns the current (active) assignment for [deviceId], if any.
     * A device has at most one active assignment at a time.
     */
    fun findByDeviceIdAndActiveTrue(deviceId: String): Optional<DeviceAssignment>

    /**
     * Returns all currently active assignments — useful when the admin UI
     * needs to render the device → restaurant map across the fleet.
     */
    fun findAllByActiveTrue(): List<DeviceAssignment>

    /**
     * Returns all devices currently assigned to [restaurantId] — used when
     * building a restaurant-scoped view or pushing playlist updates to every
     * device at a single venue.
     */
    fun findAllByRestaurantIdAndActiveTrue(restaurantId: String): List<DeviceAssignment>

    /**
     * Returns the full audit trail of assignments for [deviceId], newest first.
     */
    fun findAllByDeviceIdOrderByAssignedAtDesc(deviceId: String): List<DeviceAssignment>

    /**
     * Bulk-deactivate every currently-active row for [deviceId] in a single
     * UPDATE — avoids a load-then-save round trip when remapping a device.
     *
     * Returns the number of rows affected (0 if the device had no active
     * assignment, 1 in the normal case).
     */
    @Modifying
    @Query(
        "UPDATE DeviceAssignment a SET a.active = false " +
            "WHERE a.deviceId = :deviceId AND a.active = true",
    )
    fun deactivateCurrentForDevice(@Param("deviceId") deviceId: String): Int
}
