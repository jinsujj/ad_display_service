package me.owldev.adsignage.domain.assignment

import org.springframework.security.test.context.support.WithMockUser
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
import me.owldev.adsignage.bounded.context.assignment.domain.dto.UpdateDeviceRestaurantRequest
import me.owldev.adsignage.bounded.context.device.domain.dto.UpdateDeviceRequest
import me.owldev.adsignage.bounded.context.device.domain.dto.UpdateDeviceResponse
import me.owldev.adsignage.bounded.context.device.application.service.DeviceUpdateService
import me.owldev.adsignage.bounded.context.device.adapter.`in`.api.DeviceUpdateController
import com.fasterxml.jackson.databind.ObjectMapper
import me.owldev.adsignage.bounded.context.assignment.domain.dto.CreateAssignmentRequest
import me.owldev.adsignage.bounded.context.assignment.domain.dto.UpdateAssignmentRequest
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * Sub-AC 3 verification: REST contract of [DeviceAssignmentController].
 *
 *  - POST /api/devices/{id}/assignment → 201 + assignment payload
 *  - PUT  /api/devices/{id}/assignment → 200 + new active assignment payload
 *    (and old row deactivated)
 *  - 400 Bad Request on validation failure (blank restaurantId)
 *  - 404 Not Found when device or restaurant id is unknown
 *
 * Test setup notes:
 *  - Flyway is disabled and Hibernate DDL is used so this test does not depend
 *    on sibling sub-ACs landing the `devices` / `restaurants` tables.
 *  - The JDBC-backed [DeviceLookupPort] / [RestaurantLookupPort] beans are overridden
 *    here with in-memory fakes so the test exercises the controller +
 *    service + repository wiring without needing parent tables to exist.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(
    properties = [
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:assignment-controller-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    ]
)
@WithMockUser(roles = ["OPERATOR"])
class DeviceAssignmentControllerTest {

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
    fun `POST creates assignment and returns 201 with payload`() {
        val body = mapper.writeValueAsString(CreateAssignmentRequest(restaurantId = restaurantA))

        mockMvc.perform(
            post("/api/devices/{id}/assignment", deviceId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.assignmentId").exists())
            .andExpect(jsonPath("$.deviceId").value(deviceId))
            .andExpect(jsonPath("$.restaurantId").value(restaurantA))
            .andExpect(jsonPath("$.active").value(true))
            .andExpect(jsonPath("$.assignedAt").exists())
    }

    @Test
    fun `PUT remaps device and returns 200 with new active assignment`() {
        // seed: device assigned to A
        mockMvc.perform(
            post("/api/devices/{id}/assignment", deviceId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(CreateAssignmentRequest(restaurantA)))
        ).andExpect(status().isCreated)

        // remap to B
        mockMvc.perform(
            put("/api/devices/{id}/assignment", deviceId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(UpdateAssignmentRequest(restaurantB)))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.deviceId").value(deviceId))
            .andExpect(jsonPath("$.restaurantId").value(restaurantB))
            .andExpect(jsonPath("$.active").value(true))

        // exactly one active row, pointing at B; old row preserved as audit
        val rows = repository.findAllByDeviceIdOrderByAssignedAtDesc(deviceId)
        assert(rows.size == 2) { "expected 2 audit rows, got ${rows.size}" }
        val active = rows.filter { it.active }
        assert(active.size == 1) { "expected exactly one active row" }
        assert(active.single().restaurantId == restaurantB)
    }

    @Test
    fun `POST returns 400 when restaurantId is blank`() {
        val body = """{"restaurantId":""}"""

        mockMvc.perform(
            post("/api/devices/{id}/assignment", deviceId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.fieldErrors.restaurantId").exists())
    }

    @Test
    fun `PUT returns 400 when restaurantId is blank`() {
        val body = """{"restaurantId":""}"""

        mockMvc.perform(
            put("/api/devices/{id}/assignment", deviceId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.fieldErrors.restaurantId").exists())
    }

    @Test
    fun `POST returns 404 when device id is unknown`() {
        val body = mapper.writeValueAsString(CreateAssignmentRequest(restaurantA))

        mockMvc.perform(
            post("/api/devices/{id}/assignment", "unknown-device")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.message").exists())
    }

    @Test
    fun `POST returns 404 when restaurant id is unknown`() {
        val body = mapper.writeValueAsString(CreateAssignmentRequest("unknown-restaurant"))

        mockMvc.perform(
            post("/api/devices/{id}/assignment", deviceId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.message").exists())
    }

    @Test
    fun `PUT returns 404 when device id is unknown`() {
        val body = mapper.writeValueAsString(UpdateAssignmentRequest(restaurantA))

        mockMvc.perform(
            put("/api/devices/{id}/assignment", "unknown-device")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
        )
            .andExpect(status().isNotFound)
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
