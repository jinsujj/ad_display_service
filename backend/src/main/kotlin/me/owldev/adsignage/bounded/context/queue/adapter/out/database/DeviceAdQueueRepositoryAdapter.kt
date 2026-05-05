package me.owldev.adsignage.bounded.context.queue.adapter.out.database

import me.owldev.adsignage.bounded.context.queue.application.port.out.database.DeviceAdQueueRepositoryPort
import me.owldev.adsignage.bounded.context.queue.domain.model.DeviceAdQueue
import me.owldev.adsignage.bounded.context.queue.domain.model.DeviceAdQueueId
import org.springframework.stereotype.Repository

@Repository
class DeviceAdQueueRepositoryAdapter(
    private val deviceAdQueueRepository: DeviceAdQueueRepository,
) : DeviceAdQueueRepositoryPort {
    override fun save(queue: DeviceAdQueue): DeviceAdQueue = deviceAdQueueRepository.save(queue)
    override fun findById(id: DeviceAdQueueId): DeviceAdQueue? = deviceAdQueueRepository.findById(id).orElse(null)
    override fun findAll(): List<DeviceAdQueue> = deviceAdQueueRepository.findAll()
    override fun findAllByIdDeviceIdOrderByAddedAtDesc(deviceId: String): List<DeviceAdQueue> =
        deviceAdQueueRepository.findAllByIdDeviceIdOrderByAddedAtDesc(deviceId)
    override fun findAllByIdAdId(adId: String): List<DeviceAdQueue> =
        deviceAdQueueRepository.findAllByIdAdId(adId)
    override fun deleteOne(deviceId: String, adId: String): Int =
        deviceAdQueueRepository.deleteOne(deviceId, adId)
    override fun deleteAllByDeviceId(deviceId: String): Int =
        deviceAdQueueRepository.deleteAllByDeviceId(deviceId)
    override fun deleteAllByAdId(adId: String): Int =
        deviceAdQueueRepository.deleteAllByAdId(adId)
}
