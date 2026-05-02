package me.owldev.adsignage.sse

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.time.Instant
import java.util.UUID

/**
 * Verifies that when a [DeviceMappingChangedEvent] fires, the listener
 * forwards a MAPPING_CHANGED SSE event through the [DeviceSseRegistry] —
 * i.e. the wire that drives demo scenario #3 actually carries packets.
 */
class DeviceMappingChangedSseListenerTest {

    @Test
    fun `listener broadcasts MAPPING_CHANGED to every emitter for the affected device`() {
        val registry = DeviceSseRegistry()
        val listener = DeviceMappingChangedSseListener(registry)

        val deviceId = "device-${UUID.randomUUID()}"
        val emitter1 = CapturingEmitter()
        val emitter2 = CapturingEmitter()
        registry.register(deviceId, emitter1)
        registry.register(deviceId, emitter2)

        val event = DeviceMappingChangedEvent(
            deviceId = deviceId,
            restaurantId = "restaurant-X",
            assignmentId = UUID.randomUUID().toString(),
            assignedAt = Instant.now(),
        )

        listener.onMappingChanged(event)

        assertEquals(1, emitter1.captured.size)
        assertEquals(1, emitter2.captured.size)
    }

    @Test
    fun `listener does not error when no emitters are registered`() {
        val registry = DeviceSseRegistry()
        val listener = DeviceMappingChangedSseListener(registry)

        // No registrations — should silently no-op.
        listener.onMappingChanged(
            DeviceMappingChangedEvent(
                deviceId = "ghost-device",
                restaurantId = "restaurant-A",
                assignmentId = UUID.randomUUID().toString(),
                assignedAt = Instant.now(),
            ),
        )
    }

    @Test
    fun `listener does not deliver to other devices`() {
        val registry = DeviceSseRegistry()
        val listener = DeviceMappingChangedSseListener(registry)

        val targetDevice = "device-target"
        val otherDevice = "device-other"
        val target = CapturingEmitter()
        val other = CapturingEmitter()
        registry.register(targetDevice, target)
        registry.register(otherDevice, other)

        listener.onMappingChanged(
            DeviceMappingChangedEvent(
                deviceId = targetDevice,
                restaurantId = "restaurant-Z",
                assignmentId = UUID.randomUUID().toString(),
                assignedAt = Instant.now(),
            ),
        )

        assertEquals(1, target.captured.size, "target device must receive the broadcast")
        assertEquals(0, other.captured.size, "unrelated device must not receive it")
    }

    /** Captures every send() call so the test can assert delivery without
     *  touching a real HTTP response. */
    private class CapturingEmitter : SseEmitter() {
        val captured: MutableList<Any> = mutableListOf()
        override fun send(`object`: Any) { captured += `object` }
        override fun send(builder: SseEventBuilder) { captured += builder }
    }
}
