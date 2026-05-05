package me.owldev.adsignage.sse

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
import me.owldev.adsignage.bounded.context.assignment.adapter.out.database.DeviceAssignmentRepository
import me.owldev.adsignage.bounded.context.assignment.application.service.DeviceAssignmentService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Sub-AC 50102.2 verification: the SSE [DeviceMappingChangedSseListener] must
 * fire **after** the assignment transaction commits, and must NOT fire when
 * the transaction rolls back.
 *
 * The wiring under test is:
 *  1. [DeviceAssignmentService.createAssignment] / `.updateAssignment` runs
 *     inside `@Transactional`, persists the new active row, and publishes a
 *     [DeviceMappingChangedEvent] via Spring's
 *     [org.springframework.context.ApplicationEventPublisher].
 *  2. [DeviceMappingChangedSseListener] is annotated with
 *     `@TransactionalEventListener(phase = AFTER_COMMIT, fallbackExecution = true)`,
 *     so it only runs after the commit lands (or, when the publish call
 *     happens outside any transaction, immediately via the fallback path).
 *  3. The listener calls [SseEmitterRegistry.broadcast] for the affected
 *     deviceId, fanning the [SseEventNames.MAPPING_CHANGED] payload out to
 *     every emitter currently subscribed for that device.
 *
 * This test exercises the *full* Spring chain (service → publisher →
 * transactional event listener → registry) so we know the AFTER_COMMIT phase
 * is actually wired correctly, not just declared in source.
 *
 * # Test setup
 *  - Flyway is disabled and Hibernate DDL auto-creates the schema, so we do
 *    not need the `devices` / `restaurants` parent tables to exist.
 *  - [DeviceLookupPort] / [RestaurantLookupPort] are replaced with in-memory fakes,
 *    matching the convention of [me.owldev.adsignage.bounded.context.assignment.domain.model.DeviceAssignmentControllerTest].
 *  - A [CapturingEmitter] is registered with [SseEmitterRegistry] for the
 *    test's deviceId so we can observe how many SSE events were actually
 *    broadcast.
 *  - [RollbackHelper] (a `@Transactional` Spring bean) wraps the publishing
 *    call in a transaction we force to roll back, so we can prove the
 *    AFTER_COMMIT listener does not fire on rolled-back transactions.
 */
@SpringBootTest
@TestPropertySource(
    properties = [
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:assignment-after-commit-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    ],
)
class DeviceMappingChangedAfterCommitTest {

    @Autowired lateinit var assignmentService: DeviceAssignmentService
    @Autowired lateinit var registry: SseEmitterRegistry
    @Autowired lateinit var rollbackHelper: RollbackHelper
    @Autowired lateinit var devices: DeviceLookupPort
    @Autowired lateinit var restaurants: RestaurantLookupPort
    @Autowired lateinit var assignmentRepository: DeviceAssignmentRepository

    // Keep IDs ≤ 36 chars to fit the entity's column length constraint.
    private val deviceId = "dev-" + UUID.randomUUID().toString().take(8)
    private val restaurantA = "rest-A-" + UUID.randomUUID().toString().take(8)
    private val restaurantB = "rest-B-" + UUID.randomUUID().toString().take(8)

    @BeforeEach
    fun reset() {
        assignmentRepository.deleteAll()
        (devices as MutableLookup).set(setOf(deviceId))
        (restaurants as MutableLookup).set(setOf(restaurantA, restaurantB))
    }

    @Test
    fun `MAPPING_CHANGED is broadcast after createAssignment commits`() {
        val emitter = CapturingEmitter()
        registry.add(deviceId, emitter)

        // createAssignment is @Transactional — the listener fires AFTER
        // the method returns (commit phase), not during the call.
        assignmentService.createAssignment(deviceId, restaurantA)

        assertEquals(
            1,
            emitter.captured.size,
            "expected exactly one MAPPING_CHANGED event after commit, got ${emitter.captured.size}",
        )
    }

    @Test
    fun `MAPPING_CHANGED is broadcast after updateAssignment commits`() {
        assignmentService.createAssignment(deviceId, restaurantA)

        val emitter = CapturingEmitter()
        registry.add(deviceId, emitter)

        assignmentService.updateAssignment(deviceId, restaurantB)

        assertEquals(
            1,
            emitter.captured.size,
            "expected exactly one MAPPING_CHANGED event after remap commits",
        )
    }

    @Test
    fun `MAPPING_CHANGED is NOT broadcast when the assignment transaction rolls back`() {
        val emitter = CapturingEmitter()
        registry.add(deviceId, emitter)

        // RollbackHelper performs the assignment write inside a transaction
        // and then throws, forcing a rollback. The AFTER_COMMIT listener must
        // NOT fire — proving the broadcast is bound to commit, not to publish.
        assertThrows(IllegalStateException::class.java) {
            rollbackHelper.createAndRollback(deviceId, restaurantA)
        }

        assertEquals(
            0,
            emitter.captured.size,
            "rolled-back assignment must not produce any SSE event on the wire",
        )

        // And the DB itself must be empty — sanity check that the rollback
        // actually rolled back, otherwise the previous assertion is vacuous.
        assertTrue(
            assignmentRepository.findAllByDeviceIdOrderByAssignedAtDesc(deviceId).isEmpty(),
            "rollback should have left no rows in device_assignments",
        )
    }

    // -------------------------------------------------------------------------
    // Test config: replace JDBC-backed lookups with in-memory fakes (mirrors
    // DeviceAssignmentControllerTest), and add a transactional helper bean
    // that intentionally rolls back so we can verify AFTER_COMMIT semantics.
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

    /**
     * Wraps [DeviceAssignmentService.createAssignment] in a transaction we
     * deliberately roll back. Defined as a Spring-managed bean (rather than a
     * private method on this test) so Spring's `@Transactional` proxy is
     * actually applied — calling a `@Transactional` method on `this` inside
     * the same class would bypass the proxy and skip transactional behavior.
     */
    open class RollbackHelper(
        private val assignmentService: DeviceAssignmentService,
    ) {
        @Transactional
        open fun createAndRollback(deviceId: String, restaurantId: String) {
            assignmentService.createAssignment(deviceId, restaurantId)
            check(TransactionSynchronizationManager.isActualTransactionActive()) {
                "expected an active transaction"
            }
            // Force rollback of this outer transaction (and the inner
            // createAssignment, which propagates REQUIRED by default).
            throw IllegalStateException("rolling back to verify AFTER_COMMIT semantics")
        }
    }

    @TestConfiguration
    class FakeLookupsConfig {
        @Bean
        @Primary
        fun deviceLookup(): DeviceLookupPort = FakeDeviceLookup()

        @Bean
        @Primary
        fun restaurantLookup(): RestaurantLookupPort = FakeRestaurantLookup()

        @Bean
        fun rollbackHelper(assignmentService: DeviceAssignmentService): RollbackHelper =
            RollbackHelper(assignmentService)
    }

    /** Captures every SSE send so the test can assert delivery without HTTP. */
    private class CapturingEmitter : SseEmitter() {
        val captured: ConcurrentLinkedQueue<Any> = ConcurrentLinkedQueue()
        override fun send(`object`: Any) { captured += `object` }
        override fun send(builder: SseEventBuilder) { captured += builder }
    }
}
