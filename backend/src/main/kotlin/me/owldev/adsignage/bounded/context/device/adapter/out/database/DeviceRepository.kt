package me.owldev.adsignage.bounded.context.device.adapter.out.database

import me.owldev.adsignage.bounded.context.device.domain.model.Device
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface DeviceRepository : JpaRepository<Device, String> {
    fun findAllByOrderByRegisteredAtDesc(): List<Device>
}
