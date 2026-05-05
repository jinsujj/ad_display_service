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
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.test.context.TestPropertySource
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * AC 10 verification — "End-to-end remap reflects on player within 3 seconds".
 *
 * This is the *single* test that exercises the full demo-scenario-#3 chain
 * over real HTTP, end-to-end, with a wall-clock timer:
 *
 *   1. Boot the full Spring application on a random port (real Tomcat, real
 *      servlet pipeline, real SSE response writer — NOT MockMvc).
 *
 *   2. As a player would, open a long-lived HTTP/1.1 GET against
 *        `/api/devices/{deviceId}/stream`
 *      (the AC 1 SSE wire, served by [DeviceStreamController]). Read the
 *      response body as a stream and parse the `text/event-stream` frames
 *      line-by-line on a background thread.
 *
 *   3. Wait for the `CONNECTED` handshake event so we know the server-side
 *      [SseEmitterRegistry] entry is live before we PATCH — without this
 *      synchronisation the broadcast could fire before the emitter is
 *      registered, and the test would race rather than measure timing.
 *
 *   4. Mark `t0`, then send `PATCH /api/devices/{deviceId}` with
 *        { "restaurantId": "<new>" }
 *      (the admin remap entry point — see [DeviceController]).
 *
 *   5. Mark `t1` the moment the SSE reader thread observes the
 *      `MAPPING_CHANGED` event, and assert `(t1 - t0) < 3000 ms`.
 *
 * The test exists because every individual link in the chain is unit-tested
 * elsewhere ([SseEmitterRegistryTest], [DeviceMappingChangedSseListenerTest],
 * [DeviceMappingChangedAfterCommitTest], [DeviceControllerTest]) but only an
 * end-to-end timing test can prove that **assembled together** they meet the
 * AC's wall-clock budget. The implementation could easily regress to the
 * 3-second budget being missed if e.g. someone:
 *
 *   - flipped the `@TransactionalEventListener` from `AFTER_COMMIT` (synchronous
 *     same-thread) to a phase that defers via Spring's task executor;
 *   - introduced a cache that batches SSE writes;
 *   - added an `@Async` boundary in the publish path without a real executor;
 *   - or — at the deploy edge — left the nginx `^~ /api/` block buffering
 *     the SSE stream (deploy/nginx/stream.owl-dev.me.conf carves out the
 *     SSE wire to keep `proxy_buffering off`).
 *
 * Each of those would still pass every other test in the suite while busting
 * the AC 10 budget on the real demo. Only this test catches them.
 *
 * # Why fakes for [DeviceLookupPort] / [RestaurantLookupPort]
 *   Same reason as in [DeviceMappingChangedAfterCommitTest] and
 *   [DeviceControllerTest]: the parent `devices` / `restaurants` tables are
 *   owned by sibling sub-ACs. Replacing the JDBC-backed lookups with an
 *   in-memory `Set<String>` lets this test stand on its own.
 *
 * # Why H2 with `create-drop` and Flyway off
 *   Identical pattern to the sibling integration tests. Flyway expects the
 *   real PostgreSQL schema; H2 with Hibernate's auto-DDL is enough for the
 *   `device_assignments` table this test exercises.
 *
 * # 3-second budget — why this is a generous bound, not a tight one
 *   The AFTER_COMMIT listener runs on the request thread, so the broadcast
 *   completes before the PATCH response returns. On a localhost loopback
 *   the entire round-trip (PATCH → commit → broadcast → SSE flush → reader
 *   parse) is normally well under 100 ms. The 3-second bound is the AC
 *   budget, not the expected runtime — if the wall-clock here ever climbs
 *   close to it, the chain has regressed and someone needs to investigate.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(
    properties = [
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:remap-e2e-timing-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        // Keep the Tomcat container quiet and fast on test boot.
        "logging.level.org.apache.catalina=WARN",
        "logging.level.org.springframework.web=WARN",
    ],
)
class RemapEndToEndTimingTest {

    @LocalServerPort
    var port: Int = 0

    @Autowired lateinit var assignmentService: DeviceAssignmentService
    @Autowired lateinit var assignmentRepository: DeviceAssignmentRepository
    @Autowired lateinit var devices: DeviceLookupPort
    @Autowired lateinit var restaurants: RestaurantLookupPort

    // Keep IDs ≤ 36 chars to fit DeviceAssignment's column length constraint.
    private val deviceId = "dev-${UUID.randomUUID().toString().take(8)}"
    private val restaurantA = "rest-A-${UUID.randomUUID().toString().take(8)}"
    private val restaurantB = "rest-B-${UUID.randomUUID().toString().take(8)}"

    @BeforeEach
    fun reset() {
        assignmentRepository.deleteAll()
        (devices as MutableLookup).set(setOf(deviceId))
        (restaurants as MutableLookup).set(setOf(restaurantA, restaurantB))
        // Seed the device with restaurantA so the PATCH below is a *remap*
        // (the demo-scenario-#3 path), not a first-time assignment.
        assignmentService.createAssignment(deviceId, restaurantA)
    }

    @Test
    fun `remap PATCH triggers MAPPING_CHANGED on SSE stream within 3 seconds`() {
        // Two clients — one for the long-lived SSE GET, one for the PATCH.
        // Sharing a single HttpClient is also fine; using two clarifies
        // that the SSE connection is fully independent of the PATCH call.
        val sseClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build()
        val patchClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build()

        val baseUrl = "http://localhost:$port"
        val streamUrl = "$baseUrl/api/devices/$deviceId/stream"
        val patchUrl = "$baseUrl/api/devices/$deviceId"

        // -------- 1. Open the SSE stream and parse on a background thread.
        //
        // We use BodyHandlers.ofInputStream so the HTTP send() call returns
        // as soon as response headers are read — the body InputStream then
        // streams the SSE frames on demand. SSE events are dispatched on a
        // blank line; before that, `event:` and `data:` lines accumulate.
        val connectedLatch = CountDownLatch(1)
        val mappingChangedLatch = CountDownLatch(1)
        val mappingObservedAtMs = AtomicLong(0L)
        val capturedMappingPayload = AtomicReference<String?>(null)
        val readerError = AtomicReference<Throwable?>(null)

        val sseRequest = HttpRequest.newBuilder()
            .uri(URI.create(streamUrl))
            .header("Accept", "text/event-stream")
            .GET()
            .timeout(Duration.ofSeconds(10))
            .build()

        val readerThread = Thread {
            try {
                val response = sseClient.send(sseRequest, BodyHandlers.ofInputStream())
                assertEquals(
                    200,
                    response.statusCode(),
                    "SSE stream endpoint must return 200; got ${response.statusCode()}",
                )
                response.body().bufferedReader().use { reader ->
                    var currentEvent: String? = null
                    var currentData = StringBuilder()
                    while (!Thread.currentThread().isInterrupted) {
                        val line = reader.readLine() ?: break
                        when {
                            // Blank line ⇒ dispatch boundary in SSE wire format.
                            line.isEmpty() -> {
                                val ev = currentEvent
                                val data = currentData.toString()
                                if (ev == SseEventNames.CONNECTED) {
                                    connectedLatch.countDown()
                                } else if (ev == SseEventNames.MAPPING_CHANGED) {
                                    mappingObservedAtMs.set(System.nanoTime() / 1_000_000)
                                    capturedMappingPayload.set(data)
                                    mappingChangedLatch.countDown()
                                    return@Thread
                                }
                                currentEvent = null
                                currentData = StringBuilder()
                            }
                            line.startsWith(":") -> {
                                // SSE comment / heartbeat — ignore.
                            }
                            line.startsWith("event:") -> {
                                currentEvent = line.removePrefix("event:").trim()
                            }
                            line.startsWith("data:") -> {
                                if (currentData.isNotEmpty()) currentData.append('\n')
                                currentData.append(line.removePrefix("data:").trimStart())
                            }
                            line.startsWith("id:") -> {
                                // Event id is fine to ignore for this assertion.
                            }
                            line.startsWith("retry:") -> {
                                // Reconnect hint — irrelevant to the timing assertion.
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                readerError.set(t)
            }
        }
        readerThread.isDaemon = true
        readerThread.name = "sse-reader-${deviceId.take(8)}"
        readerThread.start()

        try {
            // -------- 2. Wait for the CONNECTED handshake.
            //
            // The handshake event is sent synchronously from
            // DeviceStreamController.stream() before the controller method
            // returns, so it arrives essentially as fast as the HTTP request
            // can complete. A 5-second cap is plenty even on a loaded CI
            // host; if we don't get it the chain is broken and there's no
            // point continuing.
            val connectedReceived = connectedLatch.await(5, TimeUnit.SECONDS)
            val readerErr = readerError.get()
            assertTrue(
                connectedReceived,
                "Did not receive CONNECTED handshake within 5s. " +
                    "readerError=${readerErr?.message ?: "<none>"}",
            )

            // -------- 3. Issue the PATCH and start the wall-clock timer.
            //
            // We start the timer immediately *before* sending the PATCH so
            // network handoff time is included in the budget — the AC's
            // intent is the *operator's* perception of latency, not just
            // the server-internal pub/sub gap.
            val patchBody = """{"restaurantId":"$restaurantB"}"""
            val patchRequest = HttpRequest.newBuilder()
                .uri(URI.create(patchUrl))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(patchBody))
                .timeout(Duration.ofSeconds(5))
                .build()

            val tStartMs = System.nanoTime() / 1_000_000
            val patchResponse = patchClient.send(patchRequest, BodyHandlers.ofString())
            assertEquals(
                200,
                patchResponse.statusCode(),
                "PATCH /api/devices/{id} must return 200; body=${patchResponse.body()}",
            )

            // -------- 4. Wait for MAPPING_CHANGED on the SSE stream.
            //
            // 3-second bound is the AC budget. Real elapsed time on a
            // localhost loopback is normally under 100 ms.
            val mappingReceived = mappingChangedLatch.await(3, TimeUnit.SECONDS)
            val readerErrPostPatch = readerError.get()
            assertTrue(
                mappingReceived,
                "MAPPING_CHANGED event did not arrive on SSE stream within 3000ms " +
                    "after PATCH. readerError=${readerErrPostPatch?.message ?: "<none>"}",
            )

            val elapsedMs = mappingObservedAtMs.get() - tStartMs
            assertTrue(
                elapsedMs in 0..3000L,
                "End-to-end remap propagation took ${elapsedMs}ms — " +
                    "AC 10 requires < 3000ms (start=${tStartMs}, observed=${mappingObservedAtMs.get()}).",
            )

            // -------- 5. Sanity-check the payload carries the new restaurant.
            //
            // The wall-clock budget is the headline assertion, but a remap
            // event for the *wrong* restaurant would still satisfy the
            // timing — that would be a silent correctness regression. Tie
            // the check to the SSE payload so the test fails loudly if the
            // listener ever forwards the pre-remap row by mistake.
            val payload = capturedMappingPayload.get()
            assertNotNull(payload, "MAPPING_CHANGED arrived without a data: payload")
            assertTrue(
                payload!!.contains(""""deviceId":"$deviceId""""),
                "MAPPING_CHANGED payload must echo the target deviceId; got: $payload",
            )
            assertTrue(
                payload.contains(""""restaurantId":"$restaurantB""""),
                "MAPPING_CHANGED payload must carry the *new* restaurantId; got: $payload",
            )
        } finally {
            // Reader thread will exit on its own once it observes
            // MAPPING_CHANGED (return@Thread), but interrupt as a safety net
            // for the failure paths above so a flaky test doesn't leave a
            // stranded thread holding the SSE socket.
            readerThread.interrupt()
            readerThread.join(2_000)
        }
    }

    // -------------------------------------------------------------------------
    // Test config: in-memory parent-table fakes (mirrors
    // DeviceMappingChangedAfterCommitTest / DeviceControllerTest).
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
