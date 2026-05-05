package me.owldev.adsignage.sse

import me.owldev.adsignage.bounded.context.assignment.application.service.DeviceAssignmentService
import me.owldev.adsignage.bounded.context.assignment.application.port.out.database.DeviceLookupPort
import me.owldev.adsignage.bounded.context.assignment.application.port.out.database.RestaurantLookupPort
import me.owldev.adsignage.bounded.context.assignment.domain.exception.AssignmentNotFoundException
import me.owldev.adsignage.bounded.context.assignment.domain.exception.DeviceNotFoundException
import me.owldev.adsignage.bounded.context.assignment.domain.exception.RestaurantNotFoundException
import me.owldev.adsignage.bounded.context.assignment.domain.exception.DeviceFieldUnsupportedException
import me.owldev.adsignage.bounded.context.assignment.domain.dto.AssignmentResponse
import me.owldev.adsignage.bounded.context.assignment.domain.dto.CreateAssignmentRequest
import me.owldev.adsignage.bounded.context.assignment.domain.dto.UpdateAssignmentRequest
import me.owldev.adsignage.bounded.context.assignment.domain.dto.UpdateDeviceRestaurantRequest
import me.owldev.adsignage.bounded.context.device.domain.dto.UpdateDeviceRequest
import me.owldev.adsignage.bounded.context.device.domain.dto.UpdateDeviceResponse
import me.owldev.adsignage.bounded.context.device.application.service.DeviceUpdateService
import me.owldev.adsignage.bounded.context.device.adapter.`in`.api.DeviceUpdateController
import me.owldev.adsignage.bounded.context.assignment.domain.model.DeviceAssignment
import me.owldev.adsignage.bounded.context.assignment.application.port.out.database.DeviceAssignmentRepositoryPort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.time.Instant
import java.util.UUID

/**
 * Unit tests for [PlaylistUpdatedSseListener] (sub-AC 50201.1).
 *
 * Asserts the schedule-mutation → SSE bridge:
 *  - on event, every active-assignment device receives a PLAYLIST_UPDATE
 *    via the registry-backed publisher
 *  - devices that do not have an active assignment never receive the event
 *  - empty active-assignment set is a benign no-op
 *  - a repository failure is logged-and-swallowed so the AdService caller
 *    never sees the post-commit listener fail
 *  - one bad emitter does not poison delivery to siblings (delegated to
 *    the registry — verified end-to-end through the listener)
 *
 * The repository is `Mockito.mock`ed because [DeviceAssignmentRepositoryPort]
 * extends [org.springframework.data.jpa.repository.JpaRepository], which
 * is a 40+-method API that we don't want to hand-stub. The listener only
 * consumes [DeviceAssignmentRepositoryPort.findAllByActiveTrue], so a focused
 * stub via `when(...).thenReturn(...)` is the lightest-weight way to
 * assert the fan-out behavior.
 */
class PlaylistUpdatedSseListenerTest {

    private lateinit var registry: SseEmitterRegistry
    private lateinit var publisher: PlaylistEventPublisher
    private lateinit var assignmentRepo: DeviceAssignmentRepositoryPort
    private lateinit var listener: PlaylistUpdatedSseListener

    @BeforeEach
    fun setup() {
        registry = SseEmitterRegistry()
        publisher = PlaylistEventPublisher(registry)
        assignmentRepo = mock(DeviceAssignmentRepositoryPort::class.java)
        listener = PlaylistUpdatedSseListener(publisher, assignmentRepo)
    }

    @Test
    fun `onPlaylistUpdated broadcasts PLAYLIST_UPDATE to every actively assigned device with a live emitter`() {
        val deviceA = "device-a-${UUID.randomUUID()}"
        val deviceB = "device-b-${UUID.randomUUID()}"

        `when`(assignmentRepo.findAllByActiveTrue()).thenReturn(
            listOf(
                assignment(deviceA, "restaurant-a"),
                assignment(deviceB, "restaurant-b"),
            ),
        )

        val emitterA = CapturingEmitter()
        val emitterB = CapturingEmitter()
        registry.add(deviceA, emitterA)
        registry.add(deviceB, emitterB)

        listener.onPlaylistUpdated(
            PlaylistUpdatedEvent(
                advertiserId = "advertiser-1",
                adId = "ad-1",
            ),
        )

        assertEquals(1, emitterA.captured.size, "device A must receive PLAYLIST_UPDATE")
        assertEquals(1, emitterB.captured.size, "device B must receive PLAYLIST_UPDATE")
        assertTrue(
            emitterA.headerLines().contains("event:" + SseEventNames.PLAYLIST_UPDATE),
            "device A's event must declare PLAYLIST_UPDATE — got=${emitterA.headerLines()}",
        )
    }

    @Test
    fun `onPlaylistUpdated does not deliver to devices without an active assignment`() {
        val mappedDevice = "device-mapped"
        val unmappedDevice = "device-unmapped"
        `when`(assignmentRepo.findAllByActiveTrue()).thenReturn(
            listOf(assignment(mappedDevice, "restaurant-1")),
        )

        val mappedEmitter = CapturingEmitter()
        val unmappedEmitter = CapturingEmitter()
        registry.add(mappedDevice, mappedEmitter)
        registry.add(unmappedDevice, unmappedEmitter)

        listener.onPlaylistUpdated(PlaylistUpdatedEvent("advertiser-1", "ad-1"))

        assertEquals(1, mappedEmitter.captured.size)
        assertEquals(
            0, unmappedEmitter.captured.size,
            "unmapped device must not receive PLAYLIST_UPDATE — only active assignments do",
        )
    }

    @Test
    fun `onPlaylistUpdated is a benign no-op when no devices are actively assigned`() {
        `when`(assignmentRepo.findAllByActiveTrue()).thenReturn(emptyList())
        // No throw — and the (unregistered) registry is left untouched.
        listener.onPlaylistUpdated(PlaylistUpdatedEvent("advertiser-1", "ad-1"))
    }

    @Test
    fun `onPlaylistUpdated swallows repository failures so the post-commit phase never re-throws`() {
        `when`(assignmentRepo.findAllByActiveTrue()).thenThrow(RuntimeException("simulated DB blip"))

        // The listener must isolate this from the caller because
        // @TransactionalEventListener(AFTER_COMMIT) is downstream of the
        // AdService transaction — re-throwing here would log a phantom
        // failure with no path to caller-side recovery.
        listener.onPlaylistUpdated(PlaylistUpdatedEvent("advertiser-1", "ad-1"))
    }

    @Test
    fun `onPlaylistUpdated keeps fanning out when one device's emitter is broken`() {
        val healthyDevice = "device-healthy"
        val brokenDevice = "device-broken"
        `when`(assignmentRepo.findAllByActiveTrue()).thenReturn(
            listOf(
                assignment(healthyDevice, "restaurant-h"),
                assignment(brokenDevice, "restaurant-b"),
            ),
        )

        val healthy = CapturingEmitter()
        val broken = ThrowingEmitter()
        registry.add(healthyDevice, healthy)
        registry.add(brokenDevice, broken)

        listener.onPlaylistUpdated(PlaylistUpdatedEvent("advertiser-1", "ad-1"))

        assertEquals(1, healthy.captured.size, "healthy device must still receive PLAYLIST_UPDATE")
        assertEquals(
            0, registry.connectionCount(brokenDevice),
            "broken emitter must be purged by the registry's broadcast()",
        )
    }

    // -------------------------------------------------------------------------
    // helpers
    // -------------------------------------------------------------------------

    private fun assignment(deviceId: String, restaurantId: String): DeviceAssignment =
        DeviceAssignment(
            id = UUID.randomUUID().toString(),
            deviceId = deviceId,
            restaurantId = restaurantId,
            assignedAt = Instant.now(),
            active = true,
        )

    /** Captures send calls without touching the servlet response. */
    private class CapturingEmitter : SseEmitter() {
        val captured: MutableList<SseEventBuilder> = mutableListOf()
        override fun send(`object`: Any) { /* unused for these tests */ }
        override fun send(builder: SseEventBuilder) { captured += builder }
        fun headerLines(): String =
            captured.flatMap { it.build() }.mapNotNull { it.data as? String }.joinToString("")
    }

    private class ThrowingEmitter : SseEmitter() {
        override fun send(`object`: Any) { throw java.io.IOException("client gone") }
        override fun send(builder: SseEventBuilder) { throw java.io.IOException("client gone") }
    }
}
