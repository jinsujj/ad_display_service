package me.owldev.adsignage.domain.video

import com.fasterxml.jackson.databind.ObjectMapper
import me.owldev.adsignage.auth.jwt.JwtService
import me.owldev.adsignage.bounded.context.advertiser.domain.model.Advertiser
import me.owldev.adsignage.bounded.context.advertiser.adapter.out.database.AdvertiserRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpHeaders
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Sub-AC 4 verification: `POST /api/videos`.
 *
 * Boots the full Spring Boot context with MockMvc so we exercise:
 *  - Multipart parsing
 *  - JWT-based security gate (POST is not in the public allow-list)
 *  - VideoController → VideoUploadService → LocalVideoStorageService → JPA
 *  - GlobalExceptionHandler mapping for the 400/413/415 error shapes
 *
 * Storage root is redirected to a JUnit `@TempDir` so tests don't write to
 * `/var/lib/adsignage/videos` on the developer's machine.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(
    properties = [
        // Sibling sub-ACs may add migrations; isolate this test by letting
        // Hibernate generate the schema for the entities currently on the
        // classpath, the same approach used by AuthSignupIntegrationTest.
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:video-controller-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        // Keep the JWT secret stable across the test run so tokens we mint
        // here verify in the SecurityFilterChain.
        "adsignage.jwt.secret=test-secret-test-secret-test-secret-test-secret",
        "adsignage.jwt.expiration-ms=3600000",
        // Cap uploads at 1 KiB so we can exercise the 413 path with a tiny
        // byte array instead of a multi-MB fixture.
        "adsignage.max-upload-size-bytes=1024",
    ],
)
class VideoControllerIntegrationTest {

    companion object {
        @TempDir
        @JvmStatic
        lateinit var storageRoot: Path

        /**
         * Re-points [me.owldev.adsignage.domain.video.storage.VideoStorageProperties.videoStoragePath]
         * at the JUnit-managed temp dir so the test never writes outside its
         * sandbox. `@TempDir` resolves before `@DynamicPropertySource` runs,
         * which is why we use the dynamic-property hook rather than a static
         * `@TestPropertySource` value.
         */
        @JvmStatic
        @DynamicPropertySource
        fun storagePath(registry: DynamicPropertyRegistry) {
            registry.add("adsignage.video-storage-path") { storageRoot.toString() }
        }
    }

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var mapper: ObjectMapper

    @Autowired
    lateinit var advertiserRepository: AdvertiserRepository

    @Autowired
    lateinit var passwordEncoder: PasswordEncoder

    @Autowired
    lateinit var jwtService: JwtService

    @Autowired
    lateinit var videoRepository: VideoRepository

    /** Bearer token for an advertiser seeded fresh per test. */
    private lateinit var bearer: String

    @BeforeEach
    fun reset() {
        videoRepository.deleteAll()
        advertiserRepository.deleteAll()

        val advertiser = Advertiser(
            email = "uploader@example.com",
            passwordHash = passwordEncoder.encode("StrongPass1"),
        )
        val saved = advertiserRepository.save(advertiser)
        bearer = "Bearer " + jwtService.issueToken(saved.id, saved.email).token
    }

    @Test
    fun `POST returns 201 with the persisted Video DTO and writes the file to disk`() {
        val bytes = "fake-mp4-bytes".toByteArray()
        val part = MockMultipartFile(
            /* name = */ "file",
            /* originalFilename = */ "promo.mp4",
            /* contentType = */ "video/mp4",
            bytes,
        )

        val response = mockMvc.perform(
            multipart("/api/videos")
                .file(part)
                .header(HttpHeaders.AUTHORIZATION, bearer)
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.id").exists())
            .andExpect(jsonPath("$.filename").exists())
            .andExpect(jsonPath("$.originalName").value("promo.mp4"))
            .andExpect(jsonPath("$.mimeType").value("video/mp4"))
            .andExpect(jsonPath("$.sizeBytes").value(bytes.size))
            .andExpect(jsonPath("$.url").exists())
            .andExpect(jsonPath("$.uploadedAt").exists())
            .andReturn()

        val body = mapper.readTree(response.response.contentAsString)
        val id = body["id"].asText()
        val filename = body["filename"].asText()
        val url = body["url"].asText()

        // URL is canonical /api/videos/{filename} — what the player playlist
        // and the streaming endpoint will both speak.
        assertEquals("/api/videos/$filename", url)

        // Bytes were actually written under the temp storage root.
        val onDisk = storageRoot.resolve(filename)
        assertTrue(onDisk.exists(), "uploaded file must exist on disk at $onDisk")
        assertEquals(bytes.size.toLong(), Files.size(onDisk))

        // Row was actually persisted with matching id + filename + size.
        val persisted = videoRepository.findById(id).orElseThrow()
        assertEquals(filename, persisted.filename)
        assertEquals(bytes.size.toLong(), persisted.sizeBytes)
        assertEquals("video/mp4", persisted.mimeType)
        assertEquals("promo.mp4", persisted.originalName)
    }

    @Test
    fun `POST without JWT is rejected with 401`() {
        val part = MockMultipartFile("file", "promo.mp4", "video/mp4", "x".toByteArray())

        mockMvc.perform(multipart("/api/videos").file(part))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `POST without the file part returns 400`() {
        mockMvc.perform(
            multipart("/api/videos")
                .header(HttpHeaders.AUTHORIZATION, bearer)
        )
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `POST of a non-MP4 returns 415 Unsupported Media Type`() {
        val part = MockMultipartFile(
            "file", "weird.zip", "application/zip", "x".toByteArray(),
        )

        mockMvc.perform(
            multipart("/api/videos")
                .file(part)
                .header(HttpHeaders.AUTHORIZATION, bearer)
        )
            .andExpect(status().isUnsupportedMediaType)
            .andExpect(jsonPath("$.message").exists())
    }

    @Test
    fun `POST that exceeds the configured size cap returns 413`() {
        // The test property pins the cap at 1024 bytes; this part is one over.
        val oversize = ByteArray(1_025) { 0 }
        val part = MockMultipartFile("file", "big.mp4", "video/mp4", oversize)

        mockMvc.perform(
            multipart("/api/videos")
                .file(part)
                .header(HttpHeaders.AUTHORIZATION, bearer)
        )
            .andExpect(status().isPayloadTooLarge)
            .andExpect(jsonPath("$.message").exists())
    }

    @Test
    fun `POST of an empty multipart body returns 400`() {
        val empty = MockMultipartFile("file", "promo.mp4", "video/mp4", ByteArray(0))

        mockMvc.perform(
            multipart("/api/videos")
                .file(empty)
                .header(HttpHeaders.AUTHORIZATION, bearer)
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.message").exists())
    }

    @Test
    fun `GET returns an empty array when no videos have been uploaded`() {
        mockMvc.perform(
            get("/api/videos")
                .header(HttpHeaders.AUTHORIZATION, bearer)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$").isArray)
            .andExpect(jsonPath("$.length()").value(0))
    }

    @Test
    fun `GET without JWT is rejected with 401 (auth-and-isolation pass landed)`() {
        // AC 4: the parent `/api/videos` collection is protected by JWT so
        // it can be ownership-filtered. SecurityConfig narrowed the
        // public ant-pattern from `/api/videos/**` to `/api/videos/*`,
        // letting the streaming child path stay open for the player while
        // pulling the list endpoint behind `.authenticated()`. A request
        // without a Bearer token must therefore land on the JSON 401
        // entry point ([JwtAuthenticationEntryPoint]), not on the list.
        mockMvc.perform(get("/api/videos"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `GET only returns videos owned by the authenticated advertiser`() {
        // Seed a *second* advertiser and attach an upload directly to that
        // owner via the repository so we don't need a second bearer token
        // mid-test. The first advertiser then hits the API with their own
        // bearer; the response must contain only their own row.
        val other = advertiserRepository.save(
            Advertiser(
                email = "other@example.com",
                passwordHash = passwordEncoder.encode("StrongPass1"),
            )
        )
        val otherVideo = videoRepository.save(
            me.owldev.adsignage.domain.video.Video(
                advertiserId = other.id,
                filename = "other-${java.util.UUID.randomUUID()}.mp4",
                originalName = "their-secret.mp4",
                mimeType = "video/mp4",
                sizeBytes = 42L,
                storagePath = storageRoot.resolve("their-secret-stub").toString(),
            )
        )

        // Upload one video as the *signed-in* advertiser via the API so it
        // goes through the real ownership-stamping path.
        val mine = MockMultipartFile(
            "file", "mine.mp4", "video/mp4", "mine-bytes".toByteArray(),
        )
        val mineResp = mockMvc.perform(
            multipart("/api/videos").file(mine).header(HttpHeaders.AUTHORIZATION, bearer)
        ).andExpect(status().isCreated).andReturn()
        val mineId = mapper.readTree(mineResp.response.contentAsString)["id"].asText()

        // The list endpoint must hide the other advertiser's row entirely.
        mockMvc.perform(
            get("/api/videos")
                .header(HttpHeaders.AUTHORIZATION, bearer)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$").isArray)
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].id").value(mineId))
            .andExpect(jsonPath("$[0].originalName").value("mine.mp4"))

        // Sanity: the other advertiser's row really is in the database;
        // we're filtering it out, not failing to seed it.
        assertTrue(
            videoRepository.findById(otherVideo.id).isPresent,
            "seed row for the other advertiser must persist",
        )
    }

    @Test
    fun `GET with a different advertiser's bearer returns only that advertiser's videos`() {
        // Symmetric check — the previous test proves "I see only mine";
        // this one proves "they see only theirs", using the same dataset.
        val other = advertiserRepository.save(
            Advertiser(
                email = "second@example.com",
                passwordHash = passwordEncoder.encode("StrongPass1"),
            )
        )
        val otherBearer = "Bearer " + jwtService.issueToken(other.id, other.email).token

        // Upload one video for me…
        val mine = MockMultipartFile("file", "mine.mp4", "video/mp4", "m".toByteArray())
        mockMvc.perform(
            multipart("/api/videos").file(mine).header(HttpHeaders.AUTHORIZATION, bearer)
        ).andExpect(status().isCreated)

        // …and one for the other advertiser.
        val theirs = MockMultipartFile("file", "theirs.mp4", "video/mp4", "t".toByteArray())
        val theirsResp = mockMvc.perform(
            multipart("/api/videos").file(theirs).header(HttpHeaders.AUTHORIZATION, otherBearer)
        ).andExpect(status().isCreated).andReturn()
        val theirsId = mapper.readTree(theirsResp.response.contentAsString)["id"].asText()

        // The other advertiser's GET sees exactly one row — their own.
        mockMvc.perform(
            get("/api/videos")
                .header(HttpHeaders.AUTHORIZATION, otherBearer)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$").isArray)
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].id").value(theirsId))
            .andExpect(jsonPath("$[0].originalName").value("theirs.mp4"))
    }

    @Test
    fun `POST stamps the authenticated advertiser's id onto the persisted row`() {
        // Cross-check at the persistence layer: the ownership column must
        // be populated by the controller → service path, not left null.
        val part = MockMultipartFile(
            "file", "owned.mp4", "video/mp4", "owned-bytes".toByteArray(),
        )
        val response = mockMvc.perform(
            multipart("/api/videos")
                .file(part)
                .header(HttpHeaders.AUTHORIZATION, bearer)
        )
            .andExpect(status().isCreated)
            .andReturn()
        val id = mapper.readTree(response.response.contentAsString)["id"].asText()

        val persisted = videoRepository.findById(id).orElseThrow()
        val owner = advertiserRepository.findByEmail("uploader@example.com").orElseThrow()
        assertEquals(
            owner.id, persisted.advertiserId,
            "uploaded video must be stamped with the JWT-asserted advertiser id",
        )
    }

    @Test
    fun `GET returns persisted videos newest-first with full metadata`() {
        // Upload two MP4s in sequence so the second has a strictly later
        // uploaded_at timestamp. Even on filesystems with whole-second
        // mtime resolution, JPA `Instant.now()` is sub-millisecond.
        val first = MockMultipartFile(
            "file", "older.mp4", "video/mp4", "first-bytes".toByteArray(),
        )
        val firstResp = mockMvc.perform(
            multipart("/api/videos").file(first).header(HttpHeaders.AUTHORIZATION, bearer)
        ).andExpect(status().isCreated).andReturn()
        val firstBody = mapper.readTree(firstResp.response.contentAsString)
        val firstId = firstBody["id"].asText()

        // Sleep 5 ms so the second insert's uploaded_at is strictly newer.
        Thread.sleep(5)

        val second = MockMultipartFile(
            "file", "newer.mp4", "video/mp4", "second-bytes-bigger".toByteArray(),
        )
        val secondResp = mockMvc.perform(
            multipart("/api/videos").file(second).header(HttpHeaders.AUTHORIZATION, bearer)
        ).andExpect(status().isCreated).andReturn()
        val secondBody = mapper.readTree(secondResp.response.contentAsString)
        val secondId = secondBody["id"].asText()

        mockMvc.perform(
            get("/api/videos")
                .header(HttpHeaders.AUTHORIZATION, bearer)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$").isArray)
            .andExpect(jsonPath("$.length()").value(2))
            // Newest first — the second upload sits at index 0.
            .andExpect(jsonPath("$[0].id").value(secondId))
            .andExpect(jsonPath("$[0].originalName").value("newer.mp4"))
            .andExpect(jsonPath("$[0].mimeType").value("video/mp4"))
            .andExpect(jsonPath("$[0].sizeBytes").value("second-bytes-bigger".length))
            .andExpect(jsonPath("$[0].url").exists())
            .andExpect(jsonPath("$[0].uploadedAt").exists())
            .andExpect(jsonPath("$[1].id").value(firstId))
            .andExpect(jsonPath("$[1].originalName").value("older.mp4"))
            .andExpect(jsonPath("$[1].sizeBytes").value("first-bytes".length))
    }

    @Test
    fun `JSON POST is rejected (controller only consumes multipart)`() {
        // Belt-and-braces: confirm the MULTIPART_FORM_DATA_VALUE consumes-rule
        // actually rejects a stray application/json POST. The Authorization
        // header must still be present so we're testing the consumes-rule
        // rather than the security filter.
        val result = mockMvc.perform(
            post("/api/videos")
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType("application/json")
                .content("""{"foo":"bar"}""")
        ).andReturn()

        // Spring maps the consumes mismatch to 415.
        assertEquals(415, result.response.status)
    }
}
