package me.owldev.adsignage.bounded.context.assignment.application.port.out.database

/**
 * device 컨텍스트로의 inter-context 조회 포트. assignment 가 매핑을 만들 때
 * 참조된 device_id 가 실제 존재하는지 검증해야 하는데, 그 진실의 원천은
 * device 컨텍스트이다. 자기 service 가 직접 device 의 어댑터를 들이지 않도록
 * 포트를 통해 한 단계 격리한다.
 */
interface DeviceLookupPort {
    fun exists(deviceId: String): Boolean
}
