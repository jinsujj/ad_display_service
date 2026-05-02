package me.owldev.adsignage.domain.ad

import com.fasterxml.jackson.databind.ObjectMapper
import me.owldev.adsignage.auth.jwt.JwtService
import me.owldev.adsignage.domain.advertiser.Advertiser
import me.owldev.adsignage.domain.advertiser.AdvertiserRepository
import me.owldev.adsignage.domain.video.Video
import me.owldev.adsignage.domain.video.VideoRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalTime
import java.util.UUID
import kotlin.test.assertEquals

/**
 * Sub-AC 2 of AC 3 verification: `PUT /api/ads/{id}/schedule`
 * (and the PATCH alias).
 *
 * Validates the full controller → service → repository wiring under a real
 * Spring Boot context, behind the production `SecurityFilterChain`:
 *
 *  - 200 OK + persisted [me.owldev.adsignage.domain.ad.dto.AdResponse] on
 *    a happy-path PUT.
 *  - 200 OK on the PATCH alias (the AC names "PUT/PATCH" — both must work).
 *  - 400 Bad Request with a field-error map for:
 *      * field-level rule violations (`null`, `Min`/`Max` on dailyPlayCount)
 *      * cross-field rule violation (`endTime <= startTime`) → maps to
 *        `fieldErrors.endTime`.
 *  - 401 Unauthorized when the JWT is missing.
 *  - 404 Not Found when the ad id is unknown *or* belongs to a different
 *    advertiser (the AC 4 isolation contract — "not yours" must look
 *    indistinguishable from "not found").
 *  - The persisted row really did mutate (round-trip via [AdRepository]).
 *
 * Setup mirrors `VideoControllerIntegrationTest`:
 *  - Flyway disabled, Hibernate `create-drop` lets us avoid pulling in
 *    sibling-AC migrations (V90 device_assignments depends on tables not
 *    yet authored under their own migrations).
 *  - JWT secret pinned so issued tokens verify in the production filter
 *    chain.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(
    properties = [
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:ad-schedule-controller-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "adsignage.jwt.secret=test-secret-test-secret-test-secret-test-secret",
        "adsignage.jwt.expiration-ms=3600000",
    ],
)
class AdScheduleControllerIntegrationTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var mapper: ObjectMapper
    @Autowired lateinit var advertiserRepository: AdvertiserRepository
    @Autowired lateinit var videoRepository: VideoRepository
    @Autowired lateinit var adRepository: AdRepository
    @Autowired lateinit var passwordEncoder: PasswordEncoder
    @Autowired lateinit var jwtService: JwtService

    private lateinit var ownerAdvertiser: Advertiser
    private lateinit var otherAdvertiser: Advertiser
    private lateinit var ownerVideo: Video
    private lateinit var otherVideo: Video
    private lateinit var ownerAd: Ad
    private lateinit var otherAd: Ad
    private lateinit var ownerBearer: String
    private lateinit var otherBearer: String

    @BeforeEach
    fun reset() {
        adRepository.deleteAll()
        videoRepository.deleteAll()
        advertiserRepository.deleteAll()

        ownerAdvertiser = advertiserRepository.save(
            Advertiser(
                email = "owner@example.com",
                passwordHash = passwordEncoder.encode("StrongPass1"),
            ),
        )
        otherAdvertiser = advertiserRepository.save(
            Advertiser(
                email = "stranger@example.com",
                passwordHash = passwordEncoder.encode("StrongPass1"),
            ),
        )

        ownerVideo = videoRepository.save(
            Video(
                advertiserId = ownerAdvertiser.id,
                filename = "owner-${UUID.randomUUID()}.mp4",
                originalName = "promo.mp4",
                mimeType = "video/mp4",
                sizeBytes = 12_345L,
                storagePath = "/tmp/owner-stub",
            ),
        )
        otherVideo = videoRepository.save(
            Video(
                advertiserId = otherAdvertiser.id,
                filename = "other-${UUID.randomUUID()}.mp4",
                originalName = "their-promo.mp4",
                mimeType = "video/mp4",
                sizeBytes = 12_345L,
                storagePath = "/tmp/other-stub",
            ),
        )

        ownerAd = adRepository.save(
            Ad(
                advertiserId = ownerAdvertiser.id,
                title = "Owner ad",
                videoFilename = ownerVideo.filename,
                startTime = LocalTime.of(9, 0),
                endTime = LocalTime.of(17, 0),
                dailyPlayCount = 50,
            ),
        )
        otherAd = adRepository.save(
            Ad(
                advertiserId = otherAdvertiser.id,
                title = "Stranger ad",
                videoFilename = otherVideo.filename,
                startTime = LocalTime.of(10, 0),
                endTime = LocalTime.of(20, 0),
                dailyPlayCount = 100,
            ),
        )

        ownerBearer = "Bearer " + jwtService
            .issueToken(ownerAdvertiser.id, ownerAdvertiser.email).token
        otherBearer = "Bearer " + jwtService
            .issueToken(otherAdvertiser.id, otherAdvertiser.email).token
    }

    // -------------------------------------------------------------------------
    // happy paths
    // -------------------------------------------------------------------------

    @Test
    fun `PUT updates schedule and returns 200 with the new payload`() {
        val body = """
            {"startTime":"08:30","endTime":"22:00","dailyPlayCount":120}
        """.trimIndent()

        mockMvc.perform(
            put("/api/ads/{id}/schedule", ownerAd.id)
                .header(HttpHeaders.AUTHORIZATION, ownerBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(ownerAd.id))
            .andExpect(jsonPath("$.advertiserId").value(ownerAdvertiser.id))
            .andExpect(jsonPath("$.title").value("Owner ad"))
            .andExpect(jsonPath("$.videoFilename").value(ownerVideo.filename))
            .andExpect(jsonPath("$.startTime").value("08:30"))
            .andExpect(jsonPath("$.endTime").value("22:00"))
            .andExpect(jsonPath("$.dailyPlayCount").value(120))

        val persisted = adRepository.findById(ownerAd.id).orElseThrow()
        assertEquals(LocalTime.of(8, 30), persisted.startTime)
        assertEquals(LocalTime.of(22, 0), persisted.endTime)
        assertEquals(120, persisted.dailyPlayCount)
    }

    @Test
    fun `PATCH alias updates schedule and returns 200`() {
        val body = """
            {"startTime":"06:00","endTime":"23:30","dailyPlayCount":1}
        """.trimIndent()

        mockMvc.perform(
            patch("/api/ads/{id}/schedule", ownerAd.id)
                .header(HttpHeaders.AUTHORIZATION, ownerBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.startTime").value("06:00"))
            .andExpect(jsonPath("$.endTime").value("23:30"))
            .andExpect(jsonPath("$.dailyPlayCount").value(1))

        val persisted = adRepository.findById(ownerAd.id).orElseThrow()
        assertEquals(LocalTime.of(6, 0), persisted.startTime)
        assertEquals(LocalTime.of(23, 30), persisted.endTime)
        assertEquals(1, persisted.dailyPlayCount)
    }

    // -------------------------------------------------------------------------
    // validation
    // -------------------------------------------------------------------------

    @Test
    fun `PUT returns 400 with field error map when endTime is not after startTime`() {
        val body = """
            {"startTime":"10:00","endTime":"10:00","dailyPlayCount":50}
        """.trimIndent()

        mockMvc.perform(
            put("/api/ads/{id}/schedule", ownerAd.id)
                .header(HttpHeaders.AUTHORIZATION, ownerBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.fieldErrors.endTime").exists())

        // The row must not have moved.
        val persisted = adRepository.findById(ownerAd.id).orElseThrow()
        assertEquals(LocalTime.of(9, 0), persisted.startTime)
        assertEquals(LocalTime.of(17, 0), persisted.endTime)
    }

    @Test
    fun `PUT returns 400 when dailyPlayCount is below 1`() {
        val body = """
            {"startTime":"09:00","endTime":"17:00","dailyPlayCount":0}
        """.trimIndent()

        mockMvc.perform(
            put("/api/ads/{id}/schedule", ownerAd.id)
                .header(HttpHeaders.AUTHORIZATION, ownerBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.fieldErrors.dailyPlayCount").exists())
    }

    @Test
    fun `PUT returns 400 when dailyPlayCount exceeds the upper bound`() {
        val body = """
            {"startTime":"09:00","endTime":"17:00","dailyPlayCount":10001}
        """.trimIndent()

        mockMvc.perform(
            put("/api/ads/{id}/schedule", ownerAd.id)
                .header(HttpHeaders.AUTHORIZATION, ownerBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.fieldErrors.dailyPlayCount").exists())
    }

    @Test
    fun `PUT returns 400 when a required field is missing from the body`() {
        // dailyPlayCount missing → @NotNull rejects.
        val body = """
            {"startTime":"09:00","endTime":"17:00"}
        """.trimIndent()

        mockMvc.perform(
            put("/api/ads/{id}/schedule", ownerAd.id)
                .header(HttpHeaders.AUTHORIZATION, ownerBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.fieldErrors.dailyPlayCount").exists())
    }

    // Note: malformed time strings (e.g. "25:99") raise
    // HttpMessageNotReadableException at Jackson's parse step. The current
    // GlobalExceptionHandler has no specific mapping for that exception
    // (it falls into the catch-all Exception handler returning 500), so we
    // do not assert on it here — that error-mapping shortfall belongs to
    // a separate sub-AC. The schedule-field AC's contract is satisfied by
    // the field-level (`@NotNull`, `@Min`, `@Max`) and cross-field
    // (`endTime > startTime`) tests above.

    // -------------------------------------------------------------------------
    // auth + ownership isolation
    // -------------------------------------------------------------------------

    @Test
    fun `PUT without JWT is rejected with 401`() {
        val body = """
            {"startTime":"09:00","endTime":"17:00","dailyPlayCount":50}
        """.trimIndent()

        mockMvc.perform(
            put("/api/ads/{id}/schedule", ownerAd.id)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
        )
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `PUT returns 404 when the ad id is unknown`() {
        val body = """
            {"startTime":"09:00","endTime":"17:00","dailyPlayCount":50}
        """.trimIndent()

        mockMvc.perform(
            put("/api/ads/{id}/schedule", "unknown-ad-id")
                .header(HttpHeaders.AUTHORIZATION, ownerBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.message").exists())
    }

    @Test
    fun `PUT returns 404 when the ad belongs to a different advertiser (isolation)`() {
        // Owner is signed in but targets the *stranger's* ad. The repository
        // predicate `findByIdAndAdvertiserId` evaluates to empty, the
        // service raises AdNotFoundException, and the handler maps to 404
        // — collapsing "not yours" into "not found" so the API never leaks
        // the existence of another advertiser's ad.
        val body = """
            {"startTime":"00:00","endTime":"23:59","dailyPlayCount":9999}
        """.trimIndent()

        mockMvc.perform(
            put("/api/ads/{id}/schedule", otherAd.id)
                .header(HttpHeaders.AUTHORIZATION, ownerBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
        )
            .andExpect(status().isNotFound)

        // The stranger's row must be untouched — proves the ownership
        // predicate actually blocked the write rather than a 404 happening
        // after a partial mutation.
        val persisted = adRepository.findById(otherAd.id).orElseThrow()
        assertEquals(LocalTime.of(10, 0), persisted.startTime)
        assertEquals(LocalTime.of(20, 0), persisted.endTime)
        assertEquals(100, persisted.dailyPlayCount)
    }
}
