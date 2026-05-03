package me.owldev.adsignage.domain.device

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "devices")
class Device(
    @Id
    @Column(name = "device_id", nullable = false, updatable = false, length = 36)
    val deviceId: String,

    @Column(name = "device_name", nullable = false, length = 255)
    var deviceName: String,

    @Column(name = "registered_at", nullable = false, updatable = false)
    val registeredAt: Instant = Instant.now(),

    @Column(name = "last_seen_at")
    var lastSeenAt: Instant? = null,
) {
    override fun equals(other: Any?): Boolean = other is Device && other.deviceId == deviceId
    override fun hashCode(): Int = deviceId.hashCode()
}
