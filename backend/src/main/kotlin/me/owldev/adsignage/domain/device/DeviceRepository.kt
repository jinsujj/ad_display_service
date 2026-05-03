package me.owldev.adsignage.domain.device

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface DeviceRepository : JpaRepository<Device, String> {
    fun findAllByOrderByRegisteredAtDesc(): List<Device>
}
