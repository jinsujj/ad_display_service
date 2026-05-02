package me.owldev.adsignage.domain.assignment

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * DeviceAssignment maps a physical signage device to a restaurant at a point in time.
 *
 * Maps to the device_assignments table created by Flyway migration V90.
 *
 * The [active] flag identifies the current/effective assignment for a device.
 * When a device is remapped (the SSE-driven demo scenario #3), the existing
 * active row is deactivated and a new active row is inserted, so the table
 * doubles as an audit log of past mappings.
 *
 * Ontology concepts represented:
 *  - device_id              → [deviceId]    (FK → devices.device_id)
 *  - device_restaurant_id   → [restaurantId] (FK → restaurants.restaurant_id)
 *  - assigned_at            → [assignedAt]
 *  - (active flag)          → [active]
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
     * Marks this assignment as no longer current. Used when a device is remapped:
     * the previously active row is deactivated and a new active row is inserted.
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
