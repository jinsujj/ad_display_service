package me.owldev.adsignage.sse

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Unit tests for [SseEmitterRegistry] (Sub-AC 1 contract).
 *
 *  - add / connectionCount basics
 *  - getByDeviceId returns a snapshot of live emitters and isolates devices
 *  - explicit remove drops a single emitter and prunes empty device entries
 *  - broadcast fans out to every emitter for a device, and not to others
 *  - a failing emitter is dropped on the next broadcast (auto-remove path)
 *  - blank deviceId is rejected at add()
 *  - register() alias delegates to add() (back-compat with AC 5 callers)
 */
class SseEmitterRegistryTest {

    private lateinit var registry: SseEmitterRegistry

    @BeforeEach
    fun setup() {
        registry = SseEmitterRegistry()
    }

    @Test
    fun `add increments connection count for that device only`() {
        val e1 = SseEmitter()
        val e2 = SseEmitter()
        registry.add("device-1", e1)
        registry.add("device-1", e2)
        registry.add("device-2", SseEmitter())

        assertEquals(2, registry.connectionCount("device-1"))
        assertEquals(1, registry.connectionCount("device-2"))
        assertEquals(0, registry.connectionCount("device-unknown"))
    }

    @Test
    fun `add rejects blank deviceId`() {
        assertThrows(IllegalArgumentException::class.java) {
            registry.add("", SseEmitter())
        }
        assertThrows(IllegalArgumentException::class.java) {
            registry.add("   ", SseEmitter())
        }
    }

    @Test
    fun `add returns the emitter for chaining`() {
        val e = SseEmitter()
        val returned = registry.add("device-1", e)
        assertSame(e, returned, "add() must return the same emitter instance")
    }

    @Test
    fun `getByDeviceId returns the live emitters for that device only`() {
        val e1 = SseEmitter()
        val e2 = SseEmitter()
        val e3 = SseEmitter()
        registry.add("device-1", e1)
        registry.add("device-1", e2)
        registry.add("device-2", e3)

        val d1 = registry.getByDeviceId("device-1")
        val d2 = registry.getByDeviceId("device-2")

        assertEquals(2, d1.size)
        assertTrue(d1.containsAll(listOf(e1, e2)))
        assertEquals(1, d2.size)
        assertTrue(d2.contains(e3))
    }

    @Test
    fun `getByDeviceId returns empty list for unknown device`() {
        val list = registry.getByDeviceId("nobody-home")
        assertTrue(list.isEmpty(), "unknown device must return empty list, not null")
    }

    @Test
    fun `getByDeviceId snapshot is decoupled from later mutations`() {
        val e1 = SseEmitter()
        registry.add("device-1", e1)

        val snapshot = registry.getByDeviceId("device-1")
        assertEquals(1, snapshot.size)

        // Mutate the registry — the previously-returned snapshot must not change.
        registry.add("device-1", SseEmitter())
        assertEquals(1, snapshot.size, "snapshot must be decoupled from registry state")
        assertEquals(2, registry.getByDeviceId("device-1").size)
    }

    @Test
    fun `getByDeviceId returns an unmodifiable view`() {
        registry.add("device-1", SseEmitter())
        val view = registry.getByDeviceId("device-1")
        assertThrows(UnsupportedOperationException::class.java) {
            (view as MutableList<SseEmitter>).add(SseEmitter())
        }
    }

    @Test
    fun `remove drops only the targeted emitter`() {
        val keep = SseEmitter()
        val drop = SseEmitter()
        registry.add("device-1", keep)
        registry.add("device-1", drop)

        registry.remove("device-1", drop)

        val live = registry.getByDeviceId("device-1")
        assertEquals(1, live.size)
        assertSame(keep, live.single())
    }

    @Test
    fun `remove on the last emitter prunes the empty device entry`() {
        val only = SseEmitter()
        registry.add("device-1", only)
        registry.remove("device-1", only)

        assertEquals(0, registry.connectionCount("device-1"))
        assertTrue(registry.getByDeviceId("device-1").isEmpty())
    }

    @Test
    fun `remove for an unknown device is a no-op`() {
        // Must not throw.
        registry.remove("ghost-device", SseEmitter())
        assertEquals(0, registry.connectionCount("ghost-device"))
    }

    @Test
    fun `register alias still works for back-compat callers`() {
        val e = SseEmitter()
        val returned = registry.register("device-1", e)
        assertSame(e, returned)
        assertEquals(1, registry.connectionCount("device-1"))
        assertSame(e, registry.getByDeviceId("device-1").single())
    }

    @Test
    fun `broadcast sends event to every emitter for the matching device`() {
        val recorder1 = RecordingEmitter()
        val recorder2 = RecordingEmitter()
        val recorder3 = RecordingEmitter()
        registry.add("device-1", recorder1)
        registry.add("device-1", recorder2)
        registry.add("device-2", recorder3)

        val event = SseEmitter.event().name("MAPPING_CHANGED").data(mapOf("k" to "v"))
        val delivered = registry.broadcast("device-1", event)

        assertEquals(2, delivered)
        assertEquals(1, recorder1.sendCount)
        assertEquals(1, recorder2.sendCount)
        assertEquals(0, recorder3.sendCount, "device-2 must not see device-1's broadcast")
    }

    @Test
    fun `broadcast to a device with no emitters returns 0`() {
        val event = SseEmitter.event().name("MAPPING_CHANGED").data("x")
        assertEquals(0, registry.broadcast("nobody-home", event))
    }

    @Test
    fun `failing emitter is removed and other emitters still receive the event`() {
        val healthy = RecordingEmitter()
        val broken = ThrowingEmitter()
        registry.add("device-1", healthy)
        registry.add("device-1", broken)

        val event = SseEmitter.event().name("MAPPING_CHANGED").data("x")
        val delivered = registry.broadcast("device-1", event)

        assertEquals(1, delivered, "only the healthy emitter should count as delivered")
        assertEquals(1, healthy.sendCount)
        assertEquals(1, registry.connectionCount("device-1"), "broken emitter should be purged")
    }

    // (Spring's SseEmitter.complete() only invokes onCompletion callbacks when
    // the emitter has been bound to a real HTTP response handler — in
    // standalone unit-test construction the handler is null, so testing the
    // callback wiring requires an integration test. The deregistration-on-
    // failure path is already covered by `failing emitter is removed and
    // other emitters still receive the event`.)

    @Test
    fun `connectionCount stays stable across many broadcasts`() {
        val recorder = RecordingEmitter()
        registry.add("device-1", recorder)

        repeat(5) {
            registry.broadcast(
                "device-1",
                SseEmitter.event().name("MAPPING_CHANGED").data("x"),
            )
        }

        assertEquals(5, recorder.sendCount)
        assertEquals(1, registry.connectionCount("device-1"))
    }

    @Test
    fun `DeviceSseRegistry typealias resolves to SseEmitterRegistry`() {
        // AC 5 callers (DeviceSseController, DeviceMappingChangedSseListener)
        // still inject `DeviceSseRegistry`; the typealias must keep that name
        // resolving to the same JVM type so DI does not register a duplicate.
        val asAlias: DeviceSseRegistry = registry
        assertSame(registry, asAlias)
        assertSame(SseEmitterRegistry::class.java, DeviceSseRegistry::class.java)
    }

    // -------------------------------------------------------------------------
    // test doubles — minimal SseEmitter subclasses that do not touch the
    // servlet response (which is null in unit tests).
    // -------------------------------------------------------------------------

    /** Captures every send() call without writing to a real response. */
    private class RecordingEmitter : SseEmitter() {
        @Volatile var sendCount: Int = 0
        val received: CopyOnWriteArrayList<Any> = CopyOnWriteArrayList()

        override fun send(`object`: Any) {
            sendCount++
            received += `object`
        }

        override fun send(builder: SseEventBuilder) {
            sendCount++
            received += builder
        }
    }

    /** Always throws on send to simulate a disconnected client. */
    private class ThrowingEmitter : SseEmitter() {
        override fun send(`object`: Any) {
            throw IOException("client gone")
        }

        override fun send(builder: SseEventBuilder) {
            throw IOException("client gone")
        }
    }
}
