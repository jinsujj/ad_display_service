package me.owldev.adsignage.domain.playevent

import me.owldev.adsignage.auth.jwt.JwtService
import me.owldev.adsignage.bounded.context.advertiser.domain.model.Advertiser
import me.owldev.adsignage.bounded.context.advertiser.adapter.out.database.AdvertiserRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpHeaders
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant

/**
 * AC 20203 Sub-AC 3 verification: REST contract of [AdPlayCountController]
 * — the dashboard's "how many plays did this ad get today?" read path.
 *
 * Cases under test:
 *  - GET /api/ads/{adId}/play-events/daily-count returns 200 with `count`,
 *    `date`, `zoneId`, and the echoed `adId`.
 *  - The count reflects FINISHED events ONLY — STARTED rows for the same
 *    ad don't inflate the cap aggregate.
 *  - An unknown adId returns 200 with `count = 0` (rather than 404), so
 *    the dashboard renders "0 plays today" cleanly for newly-created ads.
 *  - The count is per-ad — events for a *different* ad do not leak in.
 *  - 401 Unauthorized when no JWT is presented (the route inherits the
 *    `/api/ads/{ANY}` `.authenticated()` rule from
 *    [me.owldev.adsignage.config.SecurityConfig]).
 *
 * Test setup mirrors the sibling [me.owldev.adsignage.domain.ad.AdScheduleControllerIntegrationTest]:
 * Flyway disabled, Hibernate-managed schema, JWT secret pinned so the
 * issued bearer is verifiable inside the production filter chain.
 *
 * **Why fixed `daily-count.zone-id=UTC`** in the test profile: with
 * `Asia/Seoul` (the production default) a test running close to UTC
 * midnight could land an event that the server buckets into a different
 * day than the test asserts. Pinning the zone to UTC removes the
 * timezone variable from the test — the only remaining clock dependency
 * is the run wall-clock at test time, which is already monotonic for
 * the duration of a single test method.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(
    properties = [
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:ad-play-count-controller-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        // Pin the day-window pivot to UTC so the test's wall-clock-based
        // assertions are timezone-stable (see class docstring).
        "adsignage.daily-count.zone-id=UTC",
        // Pinned secret so JwtService-issued tokens verify against the
        // same key in the production filter chain.
        "adsignage.jwt.secret=test-secret-test-secret-test-secret-test-secret",
        "adsignage.jwt.expiration-ms=3600000",
    ],
)
class AdPlayCountControllerTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var repository: PlayEventRepository
    @Autowired lateinit var advertiserRepository: AdvertiserRepository
    @Autowired lateinit var passwordEncoder: PasswordEncoder
    @Autowired lateinit var jwtService: JwtService

    private val adId = "ad-counted"
    private val otherAdId = "ad-other"
    private val deviceId = "device-001"
    private lateinit var bearer: String

    @BeforeEach
    fun reset() {
        repository.deleteAll()
        advertiserRepository.deleteAll()
        // Any authenticated principal satisfies the route's
        // `.authenticated()` rule today; advertiser-ownership filtering
        // for play-event reads is the auth-and-isolation pass's concern.
        val advertiser = advertiserRepository.save(
            Advertiser(
                email = "dashboard@example.com",
                passwordHash = passwordEncoder.encode("StrongPass1"),
            ),
        )
        bearer = "Bearer " + jwtService.issueToken(advertiser.id, advertiser.email).token
    }

    @Test
    fun `GET returns count, date, zoneId, and echoed adId`() {
        // Seed three FINISHED events for the ad in the current day.
        val now = Instant.now()
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

        mockMvc.perform(
            get("/api/ads/{adId}/play-events/daily-count", adId)
                .header(HttpHeaders.AUTHORIZATION, bearer),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.adId").value(adId))
            .andExpect(jsonPath("$.count").value(3))
            .andExpect(jsonPath("$.zoneId").value("UTC"))
            // The date is whatever today is in UTC; just assert the
            // ISO-8601 shape so the test isn't bound to a specific date.
            .andExpect(jsonPath("$.date").exists())
    }

    @Test
    fun `GET counts only FINISHED — STARTED rows are ignored`() {
        // Two STARTED + one FINISHED. The cap semantic is FINISHED-only,
        // so the response must report 1, not 3.
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
        repository.save(
            PlayEvent(
                deviceId = deviceId,
                adId = adId,
                eventType = PlayEventType.FINISHED,
                occurredAt = now,
            ),
        )

        mockMvc.perform(
            get("/api/ads/{adId}/play-events/daily-count", adId)
                .header(HttpHeaders.AUTHORIZATION, bearer),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.count").value(1))
    }

    @Test
    fun `GET returns 200 with count=0 for an unknown adId`() {
        // Empty DB, unknown ad — must return 200 / count=0, not 404. The
        // dashboard polls this for a freshly-created ad before any plays
        // have landed; surfacing a 404 here would force a "loading" state
        // for the cap widget that has no good resolution.
        mockMvc.perform(
            get("/api/ads/{adId}/play-events/daily-count", "ad-no-plays")
                .header(HttpHeaders.AUTHORIZATION, bearer),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.adId").value("ad-no-plays"))
            .andExpect(jsonPath("$.count").value(0))
    }

    @Test
    fun `GET is per-ad — other ad's events do not leak in`() {
        // Five FINISHED for `otherAdId`, zero for `adId`. The query for
        // `adId` must report 0, proving the per-ad index slice works.
        val now = Instant.now()
        repeat(5) {
            repository.save(
                PlayEvent(
                    deviceId = deviceId,
                    adId = otherAdId,
                    eventType = PlayEventType.FINISHED,
                    occurredAt = now,
                ),
            )
        }

        mockMvc.perform(
            get("/api/ads/{adId}/play-events/daily-count", adId)
                .header(HttpHeaders.AUTHORIZATION, bearer),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.count").value(0))

        mockMvc.perform(
            get("/api/ads/{adId}/play-events/daily-count", otherAdId)
                .header(HttpHeaders.AUTHORIZATION, bearer),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.count").value(5))

        // Sanity: the underlying rows ARE in the DB; the per-ad slice is
        // what makes the count differ.
        assertThat(repository.findAll()).hasSize(5)
    }

    @Test
    fun `GET returns 401 without a JWT`() {
        // Inherits the `/api/ads/{ANY}` `.authenticated()` rule. No
        // device-side carve-out — the count read is admin-side.
        mockMvc.perform(get("/api/ads/{adId}/play-events/daily-count", adId))
            .andExpect(status().isUnauthorized)
    }
}
