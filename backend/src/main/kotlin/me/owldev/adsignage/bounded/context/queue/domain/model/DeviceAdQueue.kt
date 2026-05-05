package me.owldev.adsignage.bounded.context.queue.domain.model

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.io.Serializable
import java.time.Instant

/**
 * 디바이스 ↔ 광고의 명시적 큐 매핑(운영자가 "이 광고를 이 디바이스에
 * 송출"하라고 골라 담은 기록). 복합 PK `(device_id, ad_id)` 로 동일 디바이스의
 * 동일 광고 중복을 막고, `added_at` 으로 운영자가 담은 순서를 그대로 보관해
 * 어드민 모니터의 썸네일 정렬을 단순화한다.
 */
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
