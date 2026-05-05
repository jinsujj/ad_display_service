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
import me.owldev.adsignage.bounded.context.assignment.domain.dto.UpdateAssignmentRequest
import me.owldev.adsignage.bounded.context.device.domain.dto.UpdateDeviceRequest
import me.owldev.adsignage.bounded.context.device.domain.dto.UpdateDeviceResponse
import me.owldev.adsignage.bounded.context.device.application.service.DeviceUpdateService
import me.owldev.adsignage.bounded.context.device.adapter.`in`.api.DeviceUpdateController
import com.fasterxml.jackson.databind.ObjectMapper
import me.owldev.adsignage.bounded.context.assignment.domain.dto.CreateAssignmentRequest
import me.owldev.adsignage.bounded.context.assignment.domain.dto.UpdateDeviceRestaurantRequest
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
 * Sub-AC 50101.1 verification: REST contract of [DeviceRestaurantController].
 *
 *  - PATCH /api/devices/{deviceId}/restaurant → 200 + new active assignment payload
 *    (and prior active row deactivated as audit history)
 *  - 400 Bad Request when restaurantId is blank
 *  - 404 Not Found when the device or restaurant id is unknown
 *
 * Test setup mirrors [DeviceAssignmentControllerTest] — Flyway disabled,
 * Hibernate DDL auto-creates the schema, and the JDBC-backed lookups are
 * swapped for in-memory fakes so we don't depend on the parent `devices` /
 * `restaurants` tables landing in this same change.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(
    properties = [
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:device-restaurant-controller-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    ]
)
class DeviceRestaurantControllerTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var mapper: ObjectMapper
    @Autowired lateinit var repository: DeviceAssignmentRepository
    @Autowired lateinit var devices: DeviceLookupPort
    @Autowired lateinit var restaurants: RestaurantLookupPort

    private val deviceId = "device-001"
    private val restaurantA = "restaurant-A"
    private val restaurantB = "restaurant-B"

    @BeforeEach
    fun reset() {
        repository.deleteAll()
        (devices as MutableLookup).set(setOf(deviceId, "device-002"))
        (restaurants as MutableLookup).set(setOf(restaurantA, restaurantB))
    }

    @Test
    fun `PATCH remaps existing device and returns 200 with new active assignment`() {
        // seed: device assigned to A via the sibling POST endpoint
        mockMvc.perform(
            post("/api/devices/{id}/assignment", deviceId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(CreateAssignmentRequest(restaurantA)))
        ).andExpect(status().isCreated)

        // remap to B via the new PATCH endpoint
        val patchBody = mapper.writeValueAsString(UpdateDeviceRestaurantRequest(restaurantB))
        mockMvc.perform(
            patch("/api/devices/{deviceId}/restaurant", deviceId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(patchBody)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.deviceId").value(deviceId))
            .andExpect(jsonPath("$.restaurantId").value(restaurantB))
            .andExpect(jsonPath("$.active").value(true))
            .andExpect(jsonPath("$.assignmentId").exists())
            .andExpect(jsonPath("$.assignedAt").exists())

        // exactly one active row, pointing at B; old row preserved as audit
        val rows = repository.findAllByDeviceIdOrderByAssignedAtDesc(deviceId)
        assert(rows.size == 2) { "expected 2 audit rows, got ${rows.size}" }
        val active = rows.filter { it.active }
        assert(active.size == 1) { "expected exactly one active row" }
        assert(active.single().restaurantId == restaurantB)
    }

    @Test
    fun `PATCH on never-assigned device returns 200 and inserts a new active row`() {
        // No prior assignment — PATCH should still succeed and produce the
        // first active row, since updateAssignment treats "no existing
        // active row" as a no-op deactivate + insert.
        val patchBody = mapper.writeValueAsString(UpdateDeviceRestaurantRequest(restaurantA))
        mockMvc.perform(
            patch("/api/devices/{deviceId}/restaurant", deviceId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(patchBody)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.deviceId").value(deviceId))
            .andExpect(jsonPath("$.restaurantId").value(restaurantA))
            .andExpect(jsonPath("$.active").value(true))

        val rows = repository.findAllByDeviceIdOrderByAssignedAtDesc(deviceId)
        assert(rows.size == 1) { "expected 1 row, got ${rows.size}" }
        assert(rows.single().active) { "expected the single row to be active" }
        assert(rows.single().restaurantId == restaurantA)
    }

    @Test
    fun `PATCH returns 400 when restaurantId is blank`() {
        val body = """{"restaurantId":""}"""

        mockMvc.perform(
            patch("/api/devices/{deviceId}/restaurant", deviceId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.fieldErrors.restaurantId").exists())
    }

    @Test
    fun `PATCH returns 404 when device id is unknown`() {
        val body = mapper.writeValueAsString(UpdateDeviceRestaurantRequest(restaurantA))

        mockMvc.perform(
            patch("/api/devices/{deviceId}/restaurant", "unknown-device")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.message").exists())
    }

    @Test
    fun `PATCH returns 404 when restaurant id is unknown`() {
        val body = mapper.writeValueAsString(UpdateDeviceRestaurantRequest("unknown-restaurant"))

        mockMvc.perform(
            patch("/api/devices/{deviceId}/restaurant", deviceId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.message").exists())
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
