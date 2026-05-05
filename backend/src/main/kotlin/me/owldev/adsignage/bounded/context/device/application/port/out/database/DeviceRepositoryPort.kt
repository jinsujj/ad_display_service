package me.owldev.adsignage.bounded.context.device.application.port.out.database

import me.owldev.adsignage.bounded.context.device.domain.model.Device

/**
 * Device 컨텍스트의 영속 포트. 외부 컨텍스트(ad/queue/playevent/assignment 등)가
 * 디바이스 메타를 읽거나 활동 신호를 갱신할 때 이 포트만 의존하도록 통일한다.
 * Spring Data 인터페이스를 직접 들이지 않음으로써 컨텍스트 간 결합 깊이를
 * 한 단계 묶어 둔다.
 */
interface DeviceRepositoryPort {
    fun save(device: Device): Device
    fun findById(deviceId: String): Device?
    fun findAllById(deviceIds: Iterable<String>): List<Device>
    fun findAllByOrderByRegisteredAtDesc(): List<Device>
    fun existsById(deviceId: String): Boolean
    fun deleteById(deviceId: String)
}
