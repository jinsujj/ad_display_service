package me.owldev.adsignage.bounded.context.assignment.application.port.out.database

import me.owldev.adsignage.bounded.context.assignment.domain.model.DeviceAssignment

/**
 * Assignment 컨텍스트의 영속 포트. 자기 컨텍스트 service 와 외부 컨텍스트
 * (device, ad, playlist) 모두 이 포트만 들이도록 통일한다.
 */
interface DeviceAssignmentRepositoryPort {
    fun save(assignment: DeviceAssignment): DeviceAssignment
    fun findByDeviceIdAndActiveTrue(deviceId: String): DeviceAssignment?
    fun findAllByActiveTrue(): List<DeviceAssignment>
    fun findAllByRestaurantIdAndActiveTrue(restaurantId: String): List<DeviceAssignment>
    fun findAllByDeviceIdOrderByAssignedAtDesc(deviceId: String): List<DeviceAssignment>
    fun deactivateCurrentForDevice(deviceId: String): Int
    fun deleteAllByDeviceId(deviceId: String): Int
}
