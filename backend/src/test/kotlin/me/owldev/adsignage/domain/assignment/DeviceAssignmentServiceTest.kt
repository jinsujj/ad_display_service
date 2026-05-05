package me.owldev.adsignage.domain.assignment

import me.owldev.adsignage.bounded.context.assignment.application.service.DeviceAssignmentService
import me.owldev.adsignage.bounded.context.assignment.adapter.out.database.DeviceAssignmentRepository
import me.owldev.adsignage.bounded.context.assignment.application.port.out.database.DeviceLookupPort
import me.owldev.adsignage.bounded.context.assignment.application.port.out.database.RestaurantLookupPort
import me.owldev.adsignage.bounded.context.assignment.domain.exception.AssignmentNotFoundException
import me.owldev.adsignage.bounded.context.assignment.domain.exception.DeviceNotFoundException
import me.owldev.adsignage.bounded.context.assignment.domain.exception.RestaurantNotFoundException
import me.owldev.adsignage.bounded.context.assignment.domain.exception.DeviceFieldUnsupportedException
import me.owldev.adsignage.bounded.context.assignment.domain.model.DeviceAssignment
import me.owldev.adsignage.bounded.context.assignment.domain.dto.AssignmentResponse
import me.owldev.adsignage.bounded.context.assignment.domain.dto.CreateAssignmentRequest
import me.owldev.adsignage.bounded.context.assignment.domain.dto.UpdateAssignmentRequest
import me.owldev.adsignage.bounded.context.assignment.domain.dto.UpdateDeviceRestaurantRequest
import me.owldev.adsignage.bounded.context.device.domain.dto.UpdateDeviceRequest
import me.owldev.adsignage.bounded.context.device.domain.dto.UpdateDeviceResponse
import me.owldev.adsignage.bounded.context.device.application.service.DeviceUpdateService
import me.owldev.adsignage.bounded.context.device.adapter.`in`.api.DeviceUpdateController
import me.owldev.adsignage.bounded.context.assignment.adapter.out.sse.DeviceMappingChangedEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEvent
import org.springframework.context.ApplicationEventPublisher
import java.util.Optional
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Unit tests for [DeviceAssignmentService].
 *
 * Uses hand-rolled in-memory fakes (no Mockito) so the test is fast and
 * trivial to read. Sub-AC 2 contract:
 *  - createAssignment validates device + restaurant exist (404 otherwise)
 *  - updateAssignment deactivates the previous active row and inserts a new one
 *  - getCurrentAssignment returns the active row, or throws AssignmentNotFound
 *  - createAssignment on a device that already has an active row delegates to
 *    updateAssignment (idempotent "set current" semantics)
 */
class DeviceAssignmentServiceTest {

    private lateinit var repo: InMemoryAssignmentRepository
    private lateinit var devices: FakeDeviceLookup
    private lateinit var restaurants: FakeRestaurantLookup
    private lateinit var publisher: RecordingEventPublisher
    private lateinit var service: DeviceAssignmentService

    private val device1 = "device-${UUID.randomUUID()}"
    private val device2 = "device-${UUID.randomUUID()}"
    private val restaurantA = "rest-${UUID.randomUUID()}"
    private val restaurantB = "rest-${UUID.randomUUID()}"

    @BeforeEach
    fun setup() {
        repo = InMemoryAssignmentRepository()
        devices = FakeDeviceLookup(setOf(device1, device2))
        restaurants = FakeRestaurantLookup(setOf(restaurantA, restaurantB))
        publisher = RecordingEventPublisher()
        service = DeviceAssignmentService(repo, devices, restaurants, publisher)
    }

    @Test
    fun `createAssignment publishes DeviceMappingChangedEvent`() {
        val saved = service.createAssignment(device1, restaurantA)

        val events = publisher.events.filterIsInstance<DeviceMappingChangedEvent>()
        assertEquals(1, events.size)
        val ev = events.single()
        assertEquals(device1, ev.deviceId)
        assertEquals(restaurantA, ev.restaurantId)
        assertEquals(saved.id, ev.assignmentId)
    }

    @Test
    fun `updateAssignment publishes DeviceMappingChangedEvent for the new restaurant`() {
        service.createAssignment(device1, restaurantA)
        publisher.events.clear()

        val remapped = service.updateAssignment(device1, restaurantB)

        val events = publisher.events.filterIsInstance<DeviceMappingChangedEvent>()
        assertEquals(1, events.size)
        val ev = events.single()
        assertEquals(device1, ev.deviceId)
        assertEquals(restaurantB, ev.restaurantId)
        assertEquals(remapped.id, ev.assignmentId)
    }

    @Test
    fun `createAssignment over an existing active row publishes a single event for the new restaurant`() {
        service.createAssignment(device1, restaurantA)
        publisher.events.clear()

        val replaced = service.createAssignment(device1, restaurantB)

        val events = publisher.events.filterIsInstance<DeviceMappingChangedEvent>()
        assertEquals(1, events.size, "second create should publish exactly one MAPPING_CHANGED event")
        val ev = events.single()
        assertEquals(restaurantB, ev.restaurantId)
        assertEquals(replaced.id, ev.assignmentId)
    }

    @Test
    fun `createAssignment persists active row when device has no current assignment`() {
        val saved = service.createAssignment(device1, restaurantA)

        assertNotNull(saved.id)
        assertEquals(device1, saved.deviceId)
        assertEquals(restaurantA, saved.restaurantId)
        assertTrue(saved.active)
        assertEquals(1, repo.count())
    }

    @Test
    fun `createAssignment throws DeviceNotFound when device id is unknown`() {
        val ex = assertThrows(DeviceNotFoundException::class.java) {
            service.createAssignment("nope-device", restaurantA)
        }
        assertEquals("nope-device", ex.deviceId)
    }

    @Test
    fun `createAssignment throws RestaurantNotFound when restaurant id is unknown`() {
        val ex = assertThrows(RestaurantNotFoundException::class.java) {
            service.createAssignment(device1, "nope-restaurant")
        }
        assertEquals("nope-restaurant", ex.restaurantId)
    }

    @Test
    fun `createAssignment rejects blank ids`() {
        assertThrows(IllegalArgumentException::class.java) {
            service.createAssignment("", restaurantA)
        }
        assertThrows(IllegalArgumentException::class.java) {
            service.createAssignment(device1, "")
        }
    }

    @Test
    fun `updateAssignment deactivates previous active row and inserts new one`() {
        val first = service.createAssignment(device1, restaurantA)
        val second = service.updateAssignment(device1, restaurantB)

        assertNotEquals(first.id, second.id)
        assertEquals(restaurantB, second.restaurantId)
        assertTrue(second.active)

        // The previously-active row must be deactivated, but still present (audit).
        val all = repo.findAll()
        assertEquals(2, all.size)
        val previous = all.first { it.id == first.id }
        assertEquals(false, previous.active, "old row should be deactivated, kept as audit history")

        // Exactly one active row for this device.
        val activeRows = all.filter { it.deviceId == device1 && it.active }
        assertEquals(1, activeRows.size)
        assertEquals(second.id, activeRows.single().id)
    }

    @Test
    fun `createAssignment on a device with an existing active row delegates to update`() {
        val first = service.createAssignment(device1, restaurantA)
        val second = service.createAssignment(device1, restaurantB)

        assertNotEquals(first.id, second.id, "second create should produce a new row, not reuse the old id")
        assertEquals(restaurantB, second.restaurantId)

        val activeRows = repo.findAll().filter { it.deviceId == device1 && it.active }
        assertEquals(1, activeRows.size, "exactly one row may be active per device")
        assertEquals(second.id, activeRows.single().id)
    }

    @Test
    fun `getCurrentAssignment returns the active row`() {
        service.createAssignment(device1, restaurantA)
        val current = service.getCurrentAssignment(device1)
        assertEquals(device1, current.deviceId)
        assertEquals(restaurantA, current.restaurantId)
        assertTrue(current.active)
    }

    @Test
    fun `getCurrentAssignment throws AssignmentNotFound when device has no active row`() {
        val ex = assertThrows(AssignmentNotFoundException::class.java) {
            service.getCurrentAssignment(device1)
        }
        assertEquals(device1, ex.deviceId)
    }

    @Test
    fun `findCurrentAssignment returns null when device has no active row`() {
        assertEquals(null, service.findCurrentAssignment(device1))
        service.createAssignment(device1, restaurantA)
        assertNotNull(service.findCurrentAssignment(device1))
    }

    @Test
    fun `multiple devices each carry their own independent active assignment`() {
        service.createAssignment(device1, restaurantA)
        service.createAssignment(device2, restaurantB)
        service.updateAssignment(device1, restaurantB)

        assertEquals(restaurantB, service.getCurrentAssignment(device1).restaurantId)
        assertEquals(restaurantB, service.getCurrentAssignment(device2).restaurantId)

        // device1 has 2 rows total (1 inactive + 1 active), device2 has 1 active row.
        val device1Rows = repo.findAll().filter { it.deviceId == device1 }
        assertEquals(2, device1Rows.size)
        assertEquals(1, device1Rows.count { it.active })

        val device2Rows = repo.findAll().filter { it.deviceId == device2 }
        assertEquals(1, device2Rows.size)
        assertEquals(true, device2Rows.single().active)
    }

    // -------------------------------------------------------------------------
    // fakes
    // -------------------------------------------------------------------------

    private class FakeDeviceLookup(private val known: Set<String>) : DeviceLookupPort {
        override fun exists(deviceId: String): Boolean = deviceId in known
    }

    private class FakeRestaurantLookup(private val known: Set<String>) : RestaurantLookupPort {
        override fun exists(restaurantId: String): Boolean = restaurantId in known
    }

    /**
     * Records every event published through Spring's [ApplicationEventPublisher]
     * so tests can assert that the SSE bridge will be triggered after a
     * mapping change. Thread-safe because the service may publish from
     * multiple threads in production, even though this test is single-threaded.
     */
    private class RecordingEventPublisher : ApplicationEventPublisher {
        val events: CopyOnWriteArrayList<Any> = CopyOnWriteArrayList()

        override fun publishEvent(event: ApplicationEvent) {
            events += event
        }

        override fun publishEvent(event: Any) {
            events += event
        }
    }

    /**
     * 인메모리 fake — 새 헥사고날 port 인터페이스(DeviceAssignmentRepositoryPort)
     * 구현. JpaRepository surface 전체가 아닌 port 가 노출하는 메서드만.
     */
    private class InMemoryAssignmentRepository :
        me.owldev.adsignage.bounded.context.assignment.application.port.out.database.DeviceAssignmentRepositoryPort {
        private val store = LinkedHashMap<String, DeviceAssignment>()

        override fun save(assignment: DeviceAssignment): DeviceAssignment {
            store[assignment.id] = assignment
            return assignment
        }

        override fun findByDeviceIdAndActiveTrue(deviceId: String): DeviceAssignment? =
            store.values.firstOrNull { it.deviceId == deviceId && it.active }

        override fun findAllByActiveTrue(): List<DeviceAssignment> =
            store.values.filter { it.active }

        override fun findAllByRestaurantIdAndActiveTrue(restaurantId: String): List<DeviceAssignment> =
            store.values.filter { it.restaurantId == restaurantId && it.active }

        override fun findAllByDeviceIdOrderByAssignedAtDesc(deviceId: String): List<DeviceAssignment> =
            store.values.filter { it.deviceId == deviceId }
                .sortedByDescending { it.assignedAt }

        override fun deactivateCurrentForDevice(deviceId: String): Int {
            val active = store.values.filter { it.deviceId == deviceId && it.active }
            active.forEach { it.deactivate() }
            return active.size
        }

        override fun deleteAllByDeviceId(deviceId: String): Int {
            val toRemove = store.values.filter { it.deviceId == deviceId }.map { it.id }
            toRemove.forEach { store.remove(it) }
            return toRemove.size
        }

        // -- 테스트 헬퍼: 기존 단언이 사용하는 read 들 (port 외부 추가 표면) --
        fun count(): Long = store.size.toLong()
        fun findAll(): List<DeviceAssignment> = store.values.toList()
    }
}
