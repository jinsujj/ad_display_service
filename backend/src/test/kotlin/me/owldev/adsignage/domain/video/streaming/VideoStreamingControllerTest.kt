package me.owldev.adsignage.domain.video.streaming

import me.owldev.adsignage.bounded.context.video.adapter.out.storage.LocalVideoStorageAdapter
import me.owldev.adsignage.bounded.context.video.application.port.out.storage.VideoStoragePort
import me.owldev.adsignage.bounded.context.video.application.service.VideoUploadService
import me.owldev.adsignage.bounded.context.video.config.VideoStorageProperties
import me.owldev.adsignage.bounded.context.video.domain.dto.StoredVideo
import me.owldev.adsignage.bounded.context.video.domain.dto.VideoResponse
import me.owldev.adsignage.bounded.context.video.domain.exception.EmptyVideoUploadException
import me.owldev.adsignage.bounded.context.video.domain.exception.InvalidVideoMimeTypeException
import me.owldev.adsignage.bounded.context.video.domain.exception.MissingVideoFilenameException
import me.owldev.adsignage.bounded.context.video.domain.exception.UnsatisfiableRangeException
import me.owldev.adsignage.bounded.context.video.domain.exception.VideoNotFoundException
import me.owldev.adsignage.bounded.context.video.domain.exception.VideoTooLargeException
import me.owldev.adsignage.bounded.context.video.domain.exception.VideoUploadException
import me.owldev.adsignage.bounded.context.video.domain.model.Video
import me.owldev.adsignage.bounded.context.video.adapter.out.database.VideoRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.RequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * AC 14 verification: `GET /api/videos/{filename}` serves bytes from disk
 * with HTTP Range request support.
 *
 * Coverage:
 *  - Full-content responses (no `Range` header) — 200 OK with full payload,
 *    `Accept-Ranges: bytes`, correct `Content-Length`.
 *  - Single-range responses — 206 Partial Content with `Content-Range`,
 *    `Content-Length`, and the precise byte slice as the body.
 *  - Suffix range (`bytes=-N`) — last N bytes.
 *  - Open-ended range (`bytes=N-`) — N to EOF.
 *  - Unsatisfiable range (start past EOF) — 416 with
 *    `Content-Range: bytes * /size`.
 *  - Unknown filename — 404 Not Found.
 *  - Public access (no JWT required) — `SecurityConfig` allow-lists this
 *    child path so the player page can fetch without auth.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(
    properties = [
        // Streaming has nothing to verify against the schema; let Hibernate
        // build the table set so we don't depend on Flyway migrations from
        // sibling sub-ACs.
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:video-streaming-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "adsignage.jwt.secret=test-secret-test-secret-test-secret-test-secret",
        "adsignage.jwt.expiration-ms=3600000",
    ],
)
class VideoStreamingControllerTest {

    @TempDir
    lateinit var storageRoot: Path

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var videoRepository: VideoRepository

    /** Stable byte payload — every test seeds this exact buffer to disk. */
    private val payload: ByteArray = ByteArray(1024) { (it % 251).toByte() }

    private lateinit var filename: String
    private lateinit var storagePath: Path

    @BeforeEach
    fun seed() {
        videoRepository.deleteAll()

        // Write the deterministic payload to a real on-disk file so the
        // streaming endpoint has bytes to read.
        filename = "${UUID.randomUUID()}.mp4"
        storagePath = storageRoot.resolve(filename)
        Files.write(storagePath, payload)

        // Insert the matching row so findByFilename can resolve it. The row
        // carries the absolute path verbatim — matching the production upload
        // pipeline (`LocalVideoStorageAdapter.store` returns
        // `target.toString()`).
        videoRepository.save(
            Video(
                advertiserId = UUID.randomUUID().toString(),
                filename = filename,
                originalName = "promo.mp4",
                mimeType = "video/mp4",
                sizeBytes = payload.size.toLong(),
                storagePath = storagePath.toString(),
            )
        )
    }

    @Test
    fun `GET without Range header returns 200 with full payload and Accept-Ranges bytes`() {
        val result = performStreaming(get("/api/videos/$filename"))
            .andExpect(status().isOk)
            .andExpect(header().string(HttpHeaders.ACCEPT_RANGES, "bytes"))
            .andExpect(header().string(HttpHeaders.CONTENT_LENGTH, payload.size.toString()))
            .andExpect(content().contentType(MediaType.valueOf("video/mp4")))
            .andReturn()

        assertContentEquals(payload, result.response.contentAsByteArray)
    }

    @Test
    fun `GET with Range bytes=0-99 returns 206 with the first 100 bytes and proper Content-Range`() {
        val result = performStreaming(
            get("/api/videos/$filename")
                .header(HttpHeaders.RANGE, "bytes=0-99")
        )
            .andExpect(status().isPartialContent)
            .andExpect(header().string(HttpHeaders.ACCEPT_RANGES, "bytes"))
            .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes 0-99/${payload.size}"))
            .andExpect(header().string(HttpHeaders.CONTENT_LENGTH, "100"))
            .andReturn()

        assertContentEquals(
            payload.copyOfRange(0, 100),
            result.response.contentAsByteArray,
        )
    }

    @Test
    fun `GET with mid-file Range bytes=200-299 returns the matching slice`() {
        val result = performStreaming(
            get("/api/videos/$filename")
                .header(HttpHeaders.RANGE, "bytes=200-299")
        )
            .andExpect(status().isPartialContent)
            .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes 200-299/${payload.size}"))
            .andExpect(header().string(HttpHeaders.CONTENT_LENGTH, "100"))
            .andReturn()

        assertContentEquals(
            payload.copyOfRange(200, 300),
            result.response.contentAsByteArray,
        )
    }

    @Test
    fun `GET with suffix Range bytes=-50 returns the last 50 bytes`() {
        val result = performStreaming(
            get("/api/videos/$filename")
                .header(HttpHeaders.RANGE, "bytes=-50")
        )
            .andExpect(status().isPartialContent)
            .andExpect(
                header().string(
                    HttpHeaders.CONTENT_RANGE,
                    "bytes ${payload.size - 50}-${payload.size - 1}/${payload.size}",
                )
            )
            .andExpect(header().string(HttpHeaders.CONTENT_LENGTH, "50"))
            .andReturn()

        assertContentEquals(
            payload.copyOfRange(payload.size - 50, payload.size),
            result.response.contentAsByteArray,
        )
    }

    @Test
    fun `GET with open-ended Range bytes=N- returns N to EOF`() {
        val result = performStreaming(
            get("/api/videos/$filename")
                .header(HttpHeaders.RANGE, "bytes=900-")
        )
            .andExpect(status().isPartialContent)
            .andExpect(
                header().string(
                    HttpHeaders.CONTENT_RANGE,
                    "bytes 900-${payload.size - 1}/${payload.size}",
                )
            )
            .andExpect(
                header().string(HttpHeaders.CONTENT_LENGTH, (payload.size - 900).toString()),
            )
            .andReturn()

        assertContentEquals(
            payload.copyOfRange(900, payload.size),
            result.response.contentAsByteArray,
        )
    }

    @Test
    fun `GET with Range past EOF returns 416 with Content-Range bytes star size`() {
        mockMvc.perform(
            get("/api/videos/$filename")
                .header(HttpHeaders.RANGE, "bytes=99999-")
        )
            .andExpect(status().isRequestedRangeNotSatisfiable)
            .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes */${payload.size}"))
    }

    @Test
    fun `GET unknown filename returns 404`() {
        mockMvc.perform(get("/api/videos/does-not-exist.mp4"))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `GET is publicly accessible (no JWT required)`() {
        // SecurityConfig allow-lists `GET /api/videos/{star}` so the
        // unauthenticated player page can fetch bytes. We deliberately do
        // not send Authorization here — the response must still be 200.
        // Use the streaming helper so the async dispatch completes before
        // we evaluate status; otherwise downstream filters can race the
        // response writer (LinkedCaseInsensitiveMap is not thread-safe).
        performStreaming(get("/api/videos/$filename"))
            .andExpect(status().isOk)
    }

    @Test
    fun `GET with Range bytes=0-0 returns the single first byte`() {
        // Edge case: zero-length-1 range. The WebView sometimes probes with
        // `bytes=0-0` to discover Content-Length without downloading the file.
        val result = performStreaming(
            get("/api/videos/$filename")
                .header(HttpHeaders.RANGE, "bytes=0-0")
        )
            .andExpect(status().isPartialContent)
            .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes 0-0/${payload.size}"))
            .andExpect(header().string(HttpHeaders.CONTENT_LENGTH, "1"))
            .andReturn()

        assertEquals(1, result.response.contentAsByteArray.size)
        assertEquals(payload[0], result.response.contentAsByteArray[0])
    }

    @Test
    fun `GET with Range past last byte returns 416`() {
        // payload size is 1024; byte index 1024 is one past the last valid
        // index (which is 1023). Per RFC 7233 this is unsatisfiable.
        mockMvc.perform(
            get("/api/videos/$filename")
                .header(HttpHeaders.RANGE, "bytes=1024-2047")
        )
            .andExpect(status().isRequestedRangeNotSatisfiable)
            .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes */${payload.size}"))
    }

    @Test
    fun `GET with malformed Range header falls back to full content (200)`() {
        // RFC 7233 §3.1: ignore unrecognised range units. A nonsense token
        // like `weirdunit=0-9` must not 4xx the request — return 200 full.
        val result = performStreaming(
            get("/api/videos/$filename")
                .header(HttpHeaders.RANGE, "weirdunit=0-9")
        )
            .andExpect(status().isOk)
            .andExpect(header().string(HttpHeaders.ACCEPT_RANGES, "bytes"))
            .andExpect(header().string(HttpHeaders.CONTENT_LENGTH, payload.size.toString()))
            .andReturn()

        assertContentEquals(payload, result.response.contentAsByteArray)
    }

    @Test
    fun `GET when on-disk file is missing returns 404 even though row exists`() {
        // Simulate a manual cleanup or orphaned row: persist a Video row
        // pointing at a path that does not exist.
        val orphanFilename = "${UUID.randomUUID()}.mp4"
        videoRepository.save(
            Video(
                advertiserId = UUID.randomUUID().toString(),
                filename = orphanFilename,
                originalName = "orphan.mp4",
                mimeType = "video/mp4",
                sizeBytes = 100L,
                storagePath = storageRoot.resolve("does-not-exist-on-disk").toString(),
            )
        )

        mockMvc.perform(get("/api/videos/$orphanFilename"))
            .andExpect(status().isNotFound)
    }

    /**
     * Helper for `2xx` streaming-response paths.
     *
     * The controller returns `StreamingResponseBody` for full-content (200)
     * and partial-content (206) responses. With `StreamingResponseBody`,
     * MockMvc starts an async request — the controller method returns
     * immediately and the body writer runs on a separate dispatcher thread.
     * If the test reads the response (or asserts headers) before that
     * dispatcher completes, two failure modes are possible:
     *
     *  1. **Truncated body** — `contentAsByteArray` returns whatever has
     *     been flushed so far, which is timing-dependent and rarely the
     *     full payload (we've observed e.g. 216 bytes of an expected 1024).
     *  2. **`ConcurrentModificationException` from `LinkedCaseInsensitiveMap`**
     *     — Spring Security's `HeaderWriterFilter` writes additional headers
     *     after the streaming dispatch begins, racing the response writer.
     *
     * This helper performs the request, asserts that an async dispatch was
     * actually started (sanity check against silent regressions where the
     * controller stops returning `StreamingResponseBody`), and then issues a
     * synchronous async-dispatch through MockMvc so the body writer
     * completes before any caller-side assertion runs. The returned chain
     * lets each test keep its existing `.andExpect(...)` style.
     *
     * 4xx paths (404 not-found, 416 unsatisfiable-range) raise an exception
     * mapped by `GlobalExceptionHandler` to a regular synchronous
     * `ResponseEntity`, so they do *not* go through async dispatch and
     * continue to use `mockMvc.perform(...)` directly.
     */
    private fun performStreaming(builder: RequestBuilder): org.springframework.test.web.servlet.ResultActions {
        val initial: MvcResult = mockMvc.perform(builder)
            .andExpect(request().asyncStarted())
            .andReturn()
        return mockMvc.perform(asyncDispatch(initial))
    }
}
