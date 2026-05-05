package me.owldev.adsignage.bounded.context.queue.application.port.out.database

import me.owldev.adsignage.bounded.context.queue.domain.model.DeviceAdQueue
import me.owldev.adsignage.bounded.context.queue.domain.model.DeviceAdQueueId

/**
 * Queue 컨텍스트의 영속 포트. 자기 컨텍스트 service 와 외부 컨텍스트
 * (ad / device / playlist) 모두 이 포트를 통해서만 큐를 읽고 쓴다 —
 * Spring Data 인터페이스를 직접 들이지 않음으로써 컨텍스트 간 결합 깊이를
 * 한 단계 묶어 둔다.
 */
interface DeviceAdQueueRepositoryPort {
    fun save(queue: DeviceAdQueue): DeviceAdQueue
    fun findById(id: DeviceAdQueueId): DeviceAdQueue?
    fun findAll(): List<DeviceAdQueue>
    fun findAllByIdDeviceIdOrderByAddedAtDesc(deviceId: String): List<DeviceAdQueue>
    fun findAllByIdAdId(adId: String): List<DeviceAdQueue>
    fun deleteOne(deviceId: String, adId: String): Int
    fun deleteAllByDeviceId(deviceId: String): Int
    fun deleteAllByAdId(adId: String): Int
}
