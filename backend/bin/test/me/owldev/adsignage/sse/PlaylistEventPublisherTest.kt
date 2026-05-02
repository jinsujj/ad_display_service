package me.owldev.adsignage.sse

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.io.IOException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Unit tests for [PlaylistEventPublisher] (sub-AC 50003.3).
 *
 * Asserts:
 *  - the publisher pulls emitters from the registry (no separate map kept)
 *  - it broadcasts a `PLAYLIST_UPDATE`-named event to every emitter
 *  - the JSON payload object is forwarded verbatim
 *  - other devices' emitters do not receive the event
 *  - publishing for a device with zero emitters is a benign no-op
 *  - blank deviceId is rejected fail-fast on the typed and Any overloads
 *  - a misbehaving emitter is purged by the registry without poisoning
 *    delivery to healthy siblings (delegates to registry.broadcast)
 */
class PlaylistEventPublisherTest {

    private lateinit var registry: SseEmitterRegistry
    private lateinit var publisher: PlaylistEventPublisher

    @BeforeEach
    fun setup() {
        registry = SseEmitterRegistry()
        publisher = PlaylistEventPublisher(registry)
    }

    @Test
    fun `publishPlaylistUpdated broadcasts to every emitter for the target device`() {
        val deviceId = "device-${UUID.randomUUID()}"
        val e1 = RecordingEmitter()
        val e2 = RecordingEmitter()
        registry.add(deviceId, e1)
        registry.add(deviceId, e2)

        val payload = PlaylistUpdatedPayload(
            deviceId = deviceId,
            restaurantId = "restaurant-1",
            updatedAt = Instant.now(),
        )

        val delivered = publisher.publishPlaylistUpdated(deviceId, payload)

        assertEquals(2, delivered, "both emitters should receive the event")
        assertEquals(1, e1.captured.size)
        assertEquals(1, e2.captured.size)
    }

    @Test
    fun `publishPlaylistUpdated tags the event as PLAYLIST_UPDATE and carries the payload`() {
        val deviceId = "device-name-check"
        val captor = RecordingEmitter()
        registry.add(deviceId, captor)

        val payload = PlaylistUpdatedPayload(
            deviceId = deviceId,
            restaurantId = "restaurant-Z",
            updatedAt = Instant.parse("2025-01-01T00:00:00Z"),
        )
        publisher.publishPlaylistUpdated(deviceId, payload)

        assertEquals(1, captor.captured.size)
        val builder = captor.captured.single() as SseEmitter.SseEventBuilder
        // SseEventBuilder.build() exposes the wire-format chunks Spring will
        // write to the response — a Set<DataWithMediaType> where the SSE
        // header lines (`event:…`, `id:…`) appear as String entries with
        // text/plain media type, and the .data(payload) appears as a
        // separate entry with whichever media type Spring inferred for the
        // payload object. We assert both:
        //   1. an entry contains the `event:PLAYLIST_UPDATE` line
        //   2. an entry's body is the exact payload object (typed)
        val chunks = builder.build()
        val headerText = chunks
            .filter { it.data is String }
            .joinToString("") { it.data as String }
        assertTrue(
            headerText.contains("event:" + SseEventNames.PLAYLIST_UPDATE),
            "event header must declare PLAYLIST_UPDATE — got headers=$headerText",
        )
        val payloadEntry = chunks.firstOrNull { it.data === payload }
        assertNotNull(
            payloadEntry,
            "payload object must be forwarded verbatim to .data(); chunks=${chunks.map { it.data }}",
        )
    }

    @Test
    fun `publishPlaylistUpdated does not deliver to other devices`() {
        val target = "device-target"
        val other = "device-other"
        val targetEmitter = RecordingEmitter()
        val otherEmitter = RecordingEmitter()
        registry.add(target, targetEmitter)
        registry.add(other, otherEmitter)

        publisher.publishPlaylistUpdated(
            target,
            PlaylistUpdatedPayload(deviceId = target, restaurantId = "r-1"),
        )

        assertEquals(1, targetEmitter.captured.size, "target device must receive the broadcast")
        assertEquals(0, otherEmitter.captured.size, "unrelated device must not receive it")
    }

    @Test
    fun `publishPlaylistUpdated is a no-op when no emitters are registered`() {
        // No registrations — must not throw and must report 0 delivered.
        val delivered = publisher.publishPlaylistUpdated(
            "ghost-device",
            PlaylistUpdatedPayload(deviceId = "ghost-device"),
        )
        assertEquals(0, delivered)
    }

    @Test
    fun `publishPlaylistUpdated rejects blank deviceId on typed overload`() {
        assertThrows(IllegalArgumentException::class.java) {
            publisher.publishPlaylistUpdated(
                "",
                PlaylistUpdatedPayload(deviceId = "x"),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            publisher.publishPlaylistUpdated(
                "   ",
                PlaylistUpdatedPayload(deviceId = "x"),
            )
        }
    }

    @Test
    fun `publishPlaylistUpdated rejects blank deviceId on Any overload`() {
        val payload: Any = mapOf("deviceId" to "x", "restaurantId" to "y")
        assertThrows(IllegalArgumentException::class.java) {
            publisher.publishPlaylistUpdated("", payload)
        }
        assertThrows(IllegalArgumentException::class.java) {
            publisher.publishPlaylistUpdated("   ", payload)
        }
    }

    @Test
    fun `publishPlaylistUpdated Any-overload forwards arbitrary JSON-shaped payloads`() {
        val deviceId = "device-loose-payload"
        val captor = RecordingEmitter()
        registry.add(deviceId, captor)

        val loose: Any = mapOf(
            "deviceId" to deviceId,
            "restaurantId" to "restaurant-A",
            "updatedAt" to "2025-01-01T00:00:00Z",
            "playlist" to mapOf("ads" to emptyList<Any>()),
        )

        publisher.publishPlaylistUpdated(deviceId, loose)

        assertEquals(1, captor.captured.size)
        val builder = captor.captured.single() as SseEmitter.SseEventBuilder
        val chunks = builder.build()
        val headerText = chunks
            .filter { it.data is String }
            .joinToString("") { it.data as String }
        assertTrue(
            headerText.contains("event:" + SseEventNames.PLAYLIST_UPDATE),
            "event header must declare PLAYLIST_UPDATE — got headers=$headerText",
        )
        val payloadEntry = chunks.firstOrNull { it.data === loose }
        assertNotNull(
            payloadEntry,
            "Any-overload payload must be forwarded verbatim; chunks=${chunks.map { it.data }}",
        )
    }

    @Test
    fun `publishPlaylistUpdated delegates failure handling to registry — bad emitter is purged`() {
        val deviceId = "device-with-broken-listener"
        val healthy = RecordingEmitter()
        val broken = ThrowingEmitter()
        registry.add(deviceId, healthy)
        registry.add(deviceId, broken)

        val delivered = publisher.publishPlaylistUpdated(
            deviceId,
            PlaylistUpdatedPayload(deviceId = deviceId),
        )

        // Healthy emitter delivered; broken emitter purged from the registry.
        assertEquals(1, delivered)
        assertEquals(1, healthy.captured.size)
        assertEquals(
            1,
            registry.connectionCount(deviceId),
            "broken emitter must be removed by the registry's broadcast()",
        )
    }

    @Test
    fun `publishPlaylistUpdated reads emitters from the registry on every call`() {
        // Verify the publisher is not caching emitters — newly-registered
        // emitters must receive subsequent broadcasts.
        val deviceId = "device-late-join"
        val first = RecordingEmitter()
        registry.add(deviceId, first)

        publisher.publishPlaylistUpdated(
            deviceId,
            PlaylistUpdatedPayload(deviceId = deviceId),
        )
        assertEquals(1, first.captured.size)

        val late = RecordingEmitter()
        registry.add(deviceId, late)

        publisher.publishPlaylistUpdated(
            deviceId,
            PlaylistUpdatedPayload(deviceId = deviceId),
        )
        // First emitter received both broadcasts; late-joiner only the second.
        assertEquals(2, first.captured.size)
        assertEquals(1, late.captured.size)
    }

    // -------------------------------------------------------------------------
    // test doubles — minimal SseEmitter subclasses that do not touch the
    // servlet response (which is null in unit tests).
    // -------------------------------------------------------------------------

    /** Captures every send() call without writing to a real response. */
    private class RecordingEmitter : SseEmitter() {
        val captured: CopyOnWriteArrayList<Any> = CopyOnWriteArrayList()
        override fun send(`object`: Any) { captured += `object` }
        override fun send(builder: SseEventBuilder) { captured += builder }
    }

    /** Always throws on send to simulate a disconnected client. */
    private class ThrowingEmitter : SseEmitter() {
        override fun send(`object`: Any) { throw IOException("client gone") }
        override fun send(builder: SseEventBuilder) { throw IOException("client gone") }
    }
}
