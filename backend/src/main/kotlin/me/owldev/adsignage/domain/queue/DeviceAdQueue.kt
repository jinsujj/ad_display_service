package me.owldev.adsignage.domain.queue

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.io.Serializable
import java.time.Instant

@Entity
@Table(name = "device_ad_queue")
class DeviceAdQueue(
    @EmbeddedId
    val id: DeviceAdQueueId,

    @Column(name = "added_at", nullable = false, updatable = false)
    val addedAt: Instant = Instant.now(),
) {
    override fun equals(other: Any?): Boolean = other is DeviceAdQueue && other.id == id
    override fun hashCode(): Int = id.hashCode()
}

@Embeddable
data class DeviceAdQueueId(
    @Column(name = "device_id", nullable = false, length = 36)
    val deviceId: String = "",

    @Column(name = "ad_id", nullable = false, length = 36)
    val adId: String = "",
) : Serializable
