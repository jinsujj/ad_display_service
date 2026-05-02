package me.owldev.adsignage.domain.playevent

import com.fasterxml.jackson.databind.ObjectMapper
import me.owldev.adsignage.domain.playevent.dto.CreatePlayEventRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant

/**
 * AC 20202 Sub-AC 2 verification: REST contract of [PlayEventController].
 *
 * Cases under test:
 *  - POST /api/devices/{deviceId}/play-events with `STARTED` → 201 with the
 *    persisted row in the response body, AND a row in the database.
 *  - POST with `FINISHED` → idem; both rows coexist for the same deviceId
 *    and adId so the read-side aggregate can compute "started but never
 *    finished" lengths.
 *  - 400 Bad Request when `adId` is missing/blank.
 *  - 400 Bad Request when `eventType` is unknown (Jackson rejects the enum
 *    value before the handler runs; mapped to 400 by GlobalExceptionHandler).
 *  - The `occurredAt` field is OPTIONAL — server stamps `Instant.now()`
 *    when the player omits it, so a barebones client (curl) works without
 *    learning ISO-8601.
 *  - The repository's daily-cap aggregate query
 *    [PlayEventRepository.countByAdIdAndEventTypeAndOccurredAtBetween]
 *    counts only the requested type — STARTED rows do not inflate the
 *    FINISHED count even though both share the same ad_id.
 *
 * Test setup mirrors the sibling [me.owldev.adsignage.domain.assignment.DeviceControllerTest]:
 * Flyway disabled so the slice does not transitively require parent tables
 * we don't own; Hibernate creates only the tables backed by an `@Entity`
 * (PlayEvent + the unrelated entities pulled in by the boot context). The
 * controller and its security allow-list are exercised against a real
 * MockMvc dispatch so the public/anonymous contract on the route is
 * verified end-to-end.
 *
 * The existing JWT filter chain runs in this profile; the route is allow-
 * listed in [me.owldev.adsignage.config.SecurityConfig], so no JWT is
 * attached on the test requests — matching the player's anonymous device
 * contract.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(
    properties = [
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:play-event-controller-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    ]
)
class PlayEventControllerTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var mapper: ObjectMapper
    @Autowired lateinit var repository: PlayEventRepository

    private val deviceId = "device-001"
    private val adId = "ad-001"

    @BeforeEach
    fun reset() {
        repository.deleteAll()
    }

    @Test
    fun `POST STARTED returns 201 and persists a row`() {
        val body = mapper.writeValueAsString(
            CreatePlayEventRequest(
                adId = adId,
                eventType = PlayEventType.STARTED,
            ),
        )
        mockMvc.perform(
            post("/api/devices/{deviceId}/play-events", deviceId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.id").exists())
            .andExpect(jsonPath("$.deviceId").value(deviceId))
            .andExpect(jsonPath("$.adId").value(adId))
            .andExpect(jsonPath("$.eventType").value("STARTED"))
            .andExpect(jsonPath("$.occurredAt").exists())
            .andExpect(jsonPath("$.receivedAt").exists())
            // AC 20203 Sub-AC 3 — the response carries the post-increment
            // server-side daily count. STARTED doesn't increment FINISHED,
            // so the count is 0 for a fresh-day fresh-ad.
            .andExpect(jsonPath("$.serverDailyCount").value(0))

        val rows = repository.findAll()
        assertThat(rows).hasSize(1)
        val saved = rows.single()
        assertThat(saved.deviceId).isEqualTo(deviceId)
        assertThat(saved.adId).isEqualTo(adId)
        assertThat(saved.eventType).isEqualTo(PlayEventType.STARTED)
    }

    @Test
    fun `POST FINISHED also persists and coexists with STARTED rows`() {
        // STARTED + FINISHED for the same ad — the natural pairing emitted
        // by the player on a complete playthrough.
        listOf(PlayEventType.STARTED, PlayEventType.FINISHED).forEach { type ->
            val body = mapper.writeValueAsString(
                CreatePlayEventRequest(adId = adId, eventType = type),
            )
            mockMvc.perform(
                post("/api/devices/{deviceId}/play-events", deviceId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body),
            ).andExpect(status().isCreated)
        }

        val rows = repository.findAll()
        assertThat(rows).hasSize(2)
        assertThat(rows.map { it.eventType }).containsExactlyInAnyOrder(
            PlayEventType.STARTED, PlayEventType.FINISHED,
        )
    }

    @Test
    fun `POST FINISHED returns post-increment server daily count`() {
        // AC 20203 Sub-AC 3 — every POST returns the FINISHED count for
        // the ad on today's calendar day, including the row we just
        // inserted. Three FINISHEDs in a row → counts of 1, 2, 3 (the
        // service reads inside the same transaction as the write).
        val expected = listOf(1, 2, 3)
        expected.forEachIndexed { idx, count ->
            val body = mapper.writeValueAsString(
                CreatePlayEventRequest(adId = adId, eventType = PlayEventType.FINISHED),
            )
            mockMvc.perform(
                post("/api/devices/{deviceId}/play-events", deviceId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body),
            )
                .andExpect(status().isCreated)
                .andExpect(jsonPath("$.eventType").value("FINISHED"))
                .andExpect(jsonPath("$.serverDailyCount").value(count))
            assertThat(repository.findAll()).hasSize(idx + 1)
        }
    }

    @Test
    fun `POST count is per-ad — different ads are isolated`() {
        // Posting FINISHED for ad-001 doesn't bump ad-002's count, and
        // vice versa. The composite (ad_id, event_type, occurred_at) index
        // covers the per-ad slice exactly.
        mockMvc.perform(
            post("/api/devices/{deviceId}/play-events", deviceId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    mapper.writeValueAsString(
                        CreatePlayEventRequest(adId = "ad-001", eventType = PlayEventType.FINISHED),
                    ),
                ),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.adId").value("ad-001"))
            .andExpect(jsonPath("$.serverDailyCount").value(1))

        mockMvc.perform(
            post("/api/devices/{deviceId}/play-events", deviceId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    mapper.writeValueAsString(
                        CreatePlayEventRequest(adId = "ad-002", eventType = PlayEventType.FINISHED),
                    ),
                ),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.adId").value("ad-002"))
            // ad-002 sees its OWN count (1), not the cross-ad total (2).
            .andExpect(jsonPath("$.serverDailyCount").value(1))
    }

    @Test
    fun `POST returns 400 when adId is missing`() {
        // adId omitted entirely — Bean Validation @NotBlank fires and the
        // GlobalExceptionHandler maps it to a 400 with the field error.
        val body = """{"eventType":"STARTED"}"""
        mockMvc.perform(
            post("/api/devices/{deviceId}/play-events", deviceId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.fieldErrors.adId").exists())
    }

    @Test
    fun `POST returns 400 when adId is blank`() {
        val body = """{"adId":"","eventType":"STARTED"}"""
        mockMvc.perform(
            post("/api/devices/{deviceId}/play-events", deviceId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.fieldErrors.adId").exists())
    }

    @Test
    fun `POST returns 400 when eventType is unknown`() {
        // Jackson rejects "PAUSED" before the handler runs — falls through
        // to the GlobalExceptionHandler's 400 for HttpMessageNotReadable.
        val body = """{"adId":"$adId","eventType":"PAUSED"}"""
        mockMvc.perform(
            post("/api/devices/{deviceId}/play-events", deviceId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        )
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `POST without occurredAt accepts and server stamps now`() {
        // Bare-minimum payload: no occurredAt. The service falls back to
        // Instant.now() — verified by reading the persisted row.
        val before = Instant.now()
        val body = """{"adId":"$adId","eventType":"FINISHED"}"""
        mockMvc.perform(
            post("/api/devices/{deviceId}/play-events", deviceId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        ).andExpect(status().isCreated)

        val saved = repository.findAll().single()
        assertThat(saved.occurredAt).isAfterOrEqualTo(before)
        assertThat(saved.occurredAt).isBeforeOrEqualTo(Instant.now())
    }

    @Test
    fun `repository count aggregates only the requested event type`() {
        // Insert directly via the repository so the test focuses on the
        // aggregate query, not the controller path. Two STARTED + three
        // FINISHED for the same ad; the aggregate must report "3" for
        // FINISHED in the day-window and "2" for STARTED.
        val now = Instant.now()
        repeat(2) {
            repository.save(
                PlayEvent(
                    deviceId = deviceId,
                    adId = adId,
                    eventType = PlayEventType.STARTED,
                    occurredAt = now,
                ),
            )
        }
        repeat(3) {
            repository.save(
                PlayEvent(
                    deviceId = deviceId,
                    adId = adId,
                    eventType = PlayEventType.FINISHED,
                    occurredAt = now,
                ),
            )
        }

        val from = now.minusSeconds(60)
        val to = now.plusSeconds(60)
        assertThat(
            repository.countByAdIdAndEventTypeAndOccurredAtBetween(
                adId, PlayEventType.STARTED, from, to,
            ),
        ).isEqualTo(2L)
        assertThat(
            repository.countByAdIdAndEventTypeAndOccurredAtBetween(
                adId, PlayEventType.FINISHED, from, to,
            ),
        ).isEqualTo(3L)
        // A different ad's events must not be counted.
        assertThat(
            repository.countByAdIdAndEventTypeAndOccurredAtBetween(
                "ad-other", PlayEventType.FINISHED, from, to,
            ),
        ).isEqualTo(0L)
    }
}
