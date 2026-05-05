package me.owldev.adsignage.domain.assignment

import org.springframework.security.test.context.support.WithMockUser
import me.owldev.adsignage.bounded.context.assignment.application.service.DeviceAssignmentService
import me.owldev.adsignage.bounded.context.assignment.adapter.out.database.DeviceAssignmentRepository
import me.owldev.adsignage.bounded.context.device.adapter.out.database.DeviceRepository as DeviceJpa
import me.owldev.adsignage.bounded.context.device.domain.model.Device
import me.owldev.adsignage.bounded.context.assignment.application.port.out.database.DeviceLookupPort
import me.owldev.adsignage.bounded.context.assignment.application.port.out.database.RestaurantLookupPort
import me.owldev.adsignage.bounded.context.assignment.domain.exception.AssignmentNotFoundException
import me.owldev.adsignage.bounded.context.assignment.domain.exception.DeviceNotFoundException
import me.owldev.adsignage.bounded.context.assignment.domain.exception.RestaurantNotFoundException
import me.owldev.adsignage.bounded.context.assignment.domain.exception.DeviceFieldUnsupportedException
import me.owldev.adsignage.bounded.context.assignment.domain.model.DeviceAssignment
import me.owldev.adsignage.bounded.context.assignment.domain.dto.AssignmentResponse
import me.owldev.adsignage.bounded.context.assignment.domain.dto.UpdateAssignmentRequest
import me.owldev.adsignage.bounded.context.assignment.domain.dto.UpdateDeviceRestaurantRequest
import me.owldev.adsignage.bounded.context.device.domain.dto.UpdateDeviceRequest
import me.owldev.adsignage.bounded.context.device.domain.dto.UpdateDeviceResponse
import me.owldev.adsignage.bounded.context.device.application.service.DeviceUpdateService
import me.owldev.adsignage.bounded.context.device.adapter.`in`.api.DeviceUpdateController
import com.fasterxml.jackson.databind.ObjectMapper
import me.owldev.adsignage.bounded.context.assignment.domain.dto.CreateAssignmentRequest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * AC 9, Sub-AC 1 verification: REST contract of [DeviceController].
 *
 * Cases under test:
 *  - PATCH /api/devices/{deviceId} with `restaurantId` → 200 + DeviceResponse
 *    carrying the new active assignment, prior row deactivated as audit
 *  - PATCH on a never-assigned device with `restaurantId` → 200, first row
 *    inserted, no prior audit row needed
 *  - 400 Bad Request when request body has zero actionable fields
 *  - 400 Bad Request when `restaurantId` is sent as `""` (callers must
 *    omit a key to mean "no change", not send blank)
 *  - 404 Not Found when the deviceId is unknown
 *  - 404 Not Found when the supplied restaurantId is unknown
 *  - 422 Unprocessable Entity when the wire-only fields (screenName,
 *    groupName) are present, surfacing the field name to the client
 *
 * Test setup mirrors [DeviceRestaurantControllerTest]: Flyway disabled,
 * Hibernate DDL auto-creates the schema, and the JDBC-backed lookups are
 * swapped for in-memory fakes so the test does not depend on the parent
 * `devices` / `restaurants` tables being present in this same change.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(
    properties = [
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:device-controller-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    ]
)
@WithMockUser(roles = ["OPERATOR"])
class DeviceControllerTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var mapper: ObjectMapper
    @Autowired lateinit var repository: DeviceAssignmentRepository
    @Autowired lateinit var devices: DeviceLookupPort
    @Autowired lateinit var restaurants: RestaurantLookupPort
    @Autowired lateinit var deviceJpa: DeviceJpa

    private val deviceId = "device-001"
    private val restaurantA = "restaurant-A"
    private val restaurantB = "restaurant-B"

    @BeforeEach
    fun reset() {
        repository.deleteAll()
        deviceJpa.deleteAll()
        // DeviceUpdateService 가 device 컨텍스트의 진짜 DeviceRepositoryPort.existsById
        // 를 거치므로 in-memory lookup fake 만으로는 부족 — 진짜 devices 행을 삽입.
        deviceJpa.save(Device(deviceId = deviceId, deviceName = "Test 1"))
        deviceJpa.save(Device(deviceId = "device-002", deviceName = "Test 2"))
        (devices as MutableLookup).set(setOf(deviceId, "device-002"))
        (restaurants as MutableLookup).set(setOf(restaurantA, restaurantB))
    }

    @Test
    fun `PATCH device with restaurantId remaps and returns 200 with new active assignment`() {
        // Seed: device assigned to A via the assignment POST endpoint.
        mockMvc.perform(
            post("/api/devices/{id}/assignment", deviceId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(CreateAssignmentRequest(restaurantA)))
        ).andExpect(status().isCreated)

        val patchBody = mapper.writeValueAsString(UpdateDeviceRequest(restaurantId = restaurantB))
        mockMvc.perform(
            patch("/api/devices/{deviceId}", deviceId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(patchBody)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.deviceId").value(deviceId))
            .andExpect(jsonPath("$.restaurantId").value(restaurantB))
            .andExpect(jsonPath("$.assignmentId").exists())
            .andExpect(jsonPath("$.assignedAt").exists())

        // Two rows total: the deactivated A row + the active B row.
        val rows = repository.findAllByDeviceIdOrderByAssignedAtDesc(deviceId)
        assert(rows.size == 2) { "expected 2 rows (audit), got ${rows.size}" }
        val active = rows.filter { it.active }
        assert(active.size == 1) { "expected exactly one active row" }
        assert(active.single().restaurantId == restaurantB)
    }

    @Test
    fun `PATCH on never-assigned device with restaurantId inserts first active row`() {
        val patchBody = mapper.writeValueAsString(UpdateDeviceRequest(restaurantId = restaurantA))
        mockMvc.perform(
            patch("/api/devices/{deviceId}", deviceId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(patchBody)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.deviceId").value(deviceId))
            .andExpect(jsonPath("$.restaurantId").value(restaurantA))

        val rows = repository.findAllByDeviceIdOrderByAssignedAtDesc(deviceId)
        assert(rows.size == 1) { "expected 1 row, got ${rows.size}" }
        assert(rows.single().active)
    }

    @Test
    fun `PATCH returns 400 when body has no actionable fields`() {
        // Empty JSON body — every field omitted. The controller short-
        // circuits this before touching the service so callers see a
        // precise reason rather than a silent 200.
        mockMvc.perform(
            patch("/api/devices/{deviceId}", deviceId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.message").exists())
    }

    @Test
    fun `PATCH returns 400 when restaurantId is sent as blank string`() {
        // An explicit `""` is not "no change" — the contract requires
        // omission for that. Validation rejects it as 400 with a field
        // error so the admin UI can surface the form bug.
        val body = """{"restaurantId":""}"""
        mockMvc.perform(
            patch("/api/devices/{deviceId}", deviceId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.fieldErrors.restaurantId").exists())
    }

    @Test
    fun `PATCH returns 404 when device id is unknown`() {
        val body = mapper.writeValueAsString(UpdateDeviceRequest(restaurantId = restaurantA))
        mockMvc.perform(
            patch("/api/devices/{deviceId}", "unknown-device")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.message").exists())
    }

    @Test
    fun `PATCH returns 404 when restaurant id is unknown`() {
        val body = mapper.writeValueAsString(UpdateDeviceRequest(restaurantId = "unknown-restaurant"))
        mockMvc.perform(
            patch("/api/devices/{deviceId}", deviceId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.message").exists())
    }

    @Test
    fun `PATCH returns 422 when screenName is provided ahead of schema support`() {
        val body = mapper.writeValueAsString(UpdateDeviceRequest(screenName = "Lobby TV"))
        mockMvc.perform(
            patch("/api/devices/{deviceId}", deviceId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
        )
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.fieldErrors.screenName").value("not yet supported"))
    }

    @Test
    fun `PATCH returns 422 when groupName is provided ahead of schema support`() {
        val body = mapper.writeValueAsString(UpdateDeviceRequest(groupName = "north-store"))
        mockMvc.perform(
            patch("/api/devices/{deviceId}", deviceId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
        )
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.fieldErrors.groupName").value("not yet supported"))
    }

    // -------------------------------------------------------------------------
    // Test config: replace the JDBC-backed lookups with in-memory fakes so
    // this test does not depend on the `devices` / `restaurants` parent tables
    // (owned by sibling sub-ACs) being present.
    // -------------------------------------------------------------------------

    interface MutableLookup {
        fun set(ids: Set<String>)
    }

    private class FakeDeviceLookup : DeviceLookupPort, MutableLookup {
        @Volatile private var known: Set<String> = emptySet()
        override fun exists(deviceId: String): Boolean = deviceId in known
        override fun set(ids: Set<String>) { this.known = ids }
    }

    private class FakeRestaurantLookup : RestaurantLookupPort, MutableLookup {
        @Volatile private var known: Set<String> = emptySet()
        override fun exists(restaurantId: String): Boolean = restaurantId in known
        override fun set(ids: Set<String>) { this.known = ids }
    }

    @TestConfiguration
    class FakeLookupsConfig {
        @Bean
        @Primary
        fun deviceLookup(): DeviceLookupPort = FakeDeviceLookup()

        @Bean
        @Primary
        fun restaurantLookup(): RestaurantLookupPort = FakeRestaurantLookup()
    }
}
