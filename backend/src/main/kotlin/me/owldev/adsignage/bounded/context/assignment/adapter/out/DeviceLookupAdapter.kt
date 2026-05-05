package me.owldev.adsignage.bounded.context.assignment.adapter.out

import me.owldev.adsignage.bounded.context.assignment.application.port.out.database.DeviceLookupPort
import me.owldev.adsignage.bounded.context.device.application.port.out.database.DeviceRepositoryPort
import org.springframework.stereotype.Component

/**
 * device 컨텍스트의 [DeviceRepositoryPort] 를 호출하여 device 존재 여부를
 * 확인하는 inter-context 어댑터. assignment service 가 직접 device 의 어댑터를
 * 들이지 않도록 [DeviceLookupPort] 뒤에서 위임한다.
 */
@Component
class DeviceLookupAdapter(
    private val deviceRepositoryPort: DeviceRepositoryPort,
) : DeviceLookupPort {
    override fun exists(deviceId: String): Boolean = deviceRepositoryPort.existsById(deviceId)
}
