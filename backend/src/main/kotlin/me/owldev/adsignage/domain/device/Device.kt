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
    /**
     * 디바이스 활동 신호를 갱신. register / play-event / 향후 heartbeat 등
     * "이 디바이스가 살아있다" 는 신호가 들어올 때마다 호출. 시각 결정은
     * 도메인 책임이므로 [Instant.now] 도 여기서 잡는다.
     */
    fun touch() {
        this.lastSeenAt = Instant.now()
    }

    override fun equals(other: Any?): Boolean = other is Device && other.deviceId == deviceId
    override fun hashCode(): Int = deviceId.hashCode()
}
