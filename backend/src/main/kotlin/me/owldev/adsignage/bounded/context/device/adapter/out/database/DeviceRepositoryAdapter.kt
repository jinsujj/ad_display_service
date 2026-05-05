package me.owldev.adsignage.bounded.context.device.adapter.out.database

import me.owldev.adsignage.bounded.context.device.application.port.out.database.DeviceRepositoryPort
import me.owldev.adsignage.bounded.context.device.domain.model.Device
import org.springframework.stereotype.Repository

@Repository
class DeviceRepositoryAdapter(
    private val deviceRepository: DeviceRepository,
) : DeviceRepositoryPort {
    override fun save(device: Device): Device = deviceRepository.save(device)
    override fun findById(deviceId: String): Device? = deviceRepository.findById(deviceId).orElse(null)
    override fun findAllById(deviceIds: Iterable<String>): List<Device> = deviceRepository.findAllById(deviceIds)
    override fun findAllByOrderByRegisteredAtDesc(): List<Device> = deviceRepository.findAllByOrderByRegisteredAtDesc()
    override fun existsById(deviceId: String): Boolean = deviceRepository.existsById(deviceId)
    override fun deleteById(deviceId: String) { deviceRepository.deleteById(deviceId) }
}
