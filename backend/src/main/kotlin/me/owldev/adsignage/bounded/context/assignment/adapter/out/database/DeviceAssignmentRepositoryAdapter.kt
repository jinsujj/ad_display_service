package me.owldev.adsignage.bounded.context.assignment.adapter.out.database

import me.owldev.adsignage.bounded.context.assignment.application.port.out.database.DeviceAssignmentRepositoryPort
import me.owldev.adsignage.bounded.context.assignment.domain.model.DeviceAssignment
import org.springframework.stereotype.Repository

@Repository
class DeviceAssignmentRepositoryAdapter(
    private val deviceAssignmentRepository: DeviceAssignmentRepository,
) : DeviceAssignmentRepositoryPort {
    override fun save(assignment: DeviceAssignment): DeviceAssignment =
        deviceAssignmentRepository.save(assignment)
    override fun findByDeviceIdAndActiveTrue(deviceId: String): DeviceAssignment? =
        deviceAssignmentRepository.findByDeviceIdAndActiveTrue(deviceId).orElse(null)
    override fun findAllByActiveTrue(): List<DeviceAssignment> =
        deviceAssignmentRepository.findAllByActiveTrue()
    override fun findAllByRestaurantIdAndActiveTrue(restaurantId: String): List<DeviceAssignment> =
        deviceAssignmentRepository.findAllByRestaurantIdAndActiveTrue(restaurantId)
    override fun findAllByDeviceIdOrderByAssignedAtDesc(deviceId: String): List<DeviceAssignment> =
        deviceAssignmentRepository.findAllByDeviceIdOrderByAssignedAtDesc(deviceId)
    override fun deactivateCurrentForDevice(deviceId: String): Int =
        deviceAssignmentRepository.deactivateCurrentForDevice(deviceId)
    override fun deleteAllByDeviceId(deviceId: String): Int =
        deviceAssignmentRepository.deleteAllByDeviceId(deviceId)
}
