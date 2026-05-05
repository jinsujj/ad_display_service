package me.owldev.adsignage.domain.video.upload

import me.owldev.adsignage.bounded.context.video.adapter.out.storage.LocalVideoStorageAdapter
import me.owldev.adsignage.bounded.context.video.application.service.VideoUploadService
import me.owldev.adsignage.bounded.context.video.domain.dto.VideoResponse
import me.owldev.adsignage.bounded.context.video.domain.exception.EmptyVideoUploadException
import me.owldev.adsignage.bounded.context.video.domain.exception.InvalidVideoMimeTypeException
import me.owldev.adsignage.bounded.context.video.domain.exception.MissingVideoFilenameException
import me.owldev.adsignage.bounded.context.video.domain.exception.UnsatisfiableRangeException
import me.owldev.adsignage.bounded.context.video.domain.exception.VideoNotFoundException
import me.owldev.adsignage.bounded.context.video.domain.exception.VideoTooLargeException
import me.owldev.adsignage.bounded.context.video.domain.exception.VideoUploadException
import me.owldev.adsignage.bounded.context.video.domain.model.Video
import me.owldev.adsignage.bounded.context.video.application.port.out.database.VideoRepositoryPort
import me.owldev.adsignage.bounded.context.video.domain.dto.StoredVideo
import me.owldev.adsignage.bounded.context.video.config.VideoStorageProperties
import me.owldev.adsignage.bounded.context.video.application.port.out.storage.VideoStoragePort
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.mock.web.MockMultipartFile
import org.springframework.web.multipart.MultipartFile
import java.util.UUID

/**
 * Unit tests for [VideoUploadService].
 *
 * Strategy:
 *  - [VideoStoragePort] is a hand-rolled recording fake — small interface,
 *    easy to read, lets us assert *order* (validation must short-circuit
 *    before the disk is touched).
 *  - [VideoRepositoryPort] is mocked via Mockito (already on the test classpath
 *    via `spring-boot-starter-test`). Implementing every JpaRepository method
 *    by hand would dwarf the actual test logic.
 *
 * Sub-AC 3 contract under test:
 *  - Valid `video/mp4` upload → delegated to storage, persisted, returned.
 *  - Empty body → [EmptyVideoUploadException], no storage call, no DB write.
 *  - Missing original filename → [MissingVideoFilenameException], short-circuit.
 *  - Wrong / missing `Content-Type` → [InvalidVideoMimeTypeException], short-circuit.
 *  - Oversize body → [VideoTooLargeException], short-circuit.
 *  - All four exceptions extend the sealed [VideoUploadException].
 */
class VideoUploadServiceTest {

    /**
     * Stable advertiser id used for the happy-path tests below. The exact
     * value doesn't matter — we only assert it round-trips into the saved
     * `Video.advertiserId` field — but pinning it as a constant keeps the
     * intent ("the JWT-asserted owner id") obvious at every call site.
     */
    private val ADVERTISER_ID = "00000000-0000-4000-8000-0000000000aa"

    private lateinit var storage: RecordingStorageService
    private lateinit var repo: VideoRepositoryPort
    private lateinit var properties: VideoStorageProperties
    private lateinit var service: VideoUploadService

    @BeforeEach
    fun setup() {
        storage = RecordingStorageService()
        repo = mock(VideoRepositoryPort::class.java)
        // The save mock just echoes its argument back, the way the real
        // JpaRepository.save() does for a transient entity with an
        // already-set id (Video assigns its UUID in the field initializer).
        `when`(repo.save(any(Video::class.java))).thenAnswer { it.arguments[0] as Video }

        properties = VideoStorageProperties(
            videoStoragePath = "/tmp/test-not-actually-used",
            maxUploadSizeBytes = 1_000,
            allowedMimeTypes = listOf("video/mp4"),
        )
        service = VideoUploadService(storage, repo, properties)
    }

    @Test
    fun `accepts a valid mp4 upload, delegates to storage, persists Video row`() {
        val bytes = "fake-mp4-bytes".toByteArray()
        val multipart = MockMultipartFile(
            /* name = */ "file",
            /* originalFilename = */ "promo.mp4",
            /* contentType = */ "video/mp4",
            bytes,
        )

        val saved = service.upload(multipart, ADVERTISER_ID)

        // Storage was called exactly once with the same multipart instance.
        assertThat(storage.calls).hasSize(1)
        assertThat(storage.calls.single()).isSameAs(multipart)

        // Repository was asked to persist a Video with the storage-side metadata.
        verify(repo).save(any(Video::class.java))
        assertThat(saved.originalName).isEqualTo("promo.mp4")
        assertThat(saved.mimeType).isEqualTo("video/mp4")
        assertThat(saved.sizeBytes).isEqualTo(bytes.size.toLong())
    }

    @Test
    fun `stamps the JWT-asserted advertiserId onto the persisted Video row`() {
        val multipart = MockMultipartFile(
            "file", "promo.mp4", "video/mp4", "x".toByteArray(),
        )

        val saved = service.upload(multipart, ADVERTISER_ID)

        // AC 4 contract: every video carries its uploader's id so the
        // admin list can filter on it. The service is the *only* place
        // this column is set, so a missing assignment here would silently
        // produce un-owned rows.
        assertThat(saved.advertiserId).isEqualTo(ADVERTISER_ID)
    }

    @Test
    fun `rejects upload with a blank advertiserId before touching storage`() {
        val multipart = MockMultipartFile(
            "file", "promo.mp4", "video/mp4", "x".toByteArray(),
        )

        // Defence in depth: the controller's @AuthenticationPrincipal is
        // declared non-null so this path shouldn't be reachable in
        // production, but a future code path that calls the service
        // directly (e.g. a seed-data importer) should fail loudly rather
        // than silently insert an orphan row.
        assertThrows<IllegalArgumentException> { service.upload(multipart, "") }
        assertThat(storage.calls).isEmpty()
    }

    @Test
    fun `accepts case-insensitive MIME type variations`() {
        val multipart = MockMultipartFile(
            "file", "promo.mp4", "Video/MP4", "x".toByteArray(),
        )

        // Should not throw — comparison is case-insensitive.
        service.upload(multipart, ADVERTISER_ID)
        assertThat(storage.calls).hasSize(1)
    }

    @Test
    fun `rejects empty multipart with EmptyVideoUploadException before touching storage`() {
        val multipart = MockMultipartFile("file", "promo.mp4", "video/mp4", ByteArray(0))

        assertThrows<EmptyVideoUploadException> { service.upload(multipart, ADVERTISER_ID) }
        assertThat(storage.calls).isEmpty()
        verify(repo, never()).save(any(Video::class.java))
    }

    @Test
    fun `rejects upload with no original filename`() {
        val multipart = MockMultipartFile("file", "", "video/mp4", "x".toByteArray())

        assertThrows<MissingVideoFilenameException> { service.upload(multipart, ADVERTISER_ID) }
        assertThat(storage.calls).isEmpty()
    }

    @Test
    fun `rejects upload whose Content-Type is not video mp4`() {
        val multipart = MockMultipartFile(
            "file", "promo.mp4", "application/octet-stream", "x".toByteArray(),
        )

        val ex = assertThrows<InvalidVideoMimeTypeException> { service.upload(multipart, ADVERTISER_ID) }
        assertThat(ex.actual).isEqualTo("application/octet-stream")
        assertThat(ex.allowed).contains("video/mp4")
        assertThat(storage.calls).isEmpty()
    }

    @Test
    fun `rejects upload whose Content-Type is missing entirely`() {
        val multipart = MockMultipartFile(
            "file", "promo.mp4", /* contentType = */ null, "x".toByteArray(),
        )

        val ex = assertThrows<InvalidVideoMimeTypeException> { service.upload(multipart, ADVERTISER_ID) }
        assertThat(ex.actual).isNull()
        assertThat(storage.calls).isEmpty()
    }

    @Test
    fun `rejects upload that exceeds the configured size limit`() {
        // Service property is 1_000 bytes; this multipart is one byte over.
        val oversize = ByteArray(1_001) { 0 }
        val multipart = MockMultipartFile("file", "promo.mp4", "video/mp4", oversize)

        val ex = assertThrows<VideoTooLargeException> { service.upload(multipart, ADVERTISER_ID) }
        assertThat(ex.actualBytes).isEqualTo(1_001)
        assertThat(ex.maxBytes).isEqualTo(1_000)
        assertThat(storage.calls).isEmpty()
    }

    @Test
    fun `accepts upload exactly at the size limit (boundary)`() {
        val atLimit = ByteArray(1_000) { 0 }
        val multipart = MockMultipartFile("file", "promo.mp4", "video/mp4", atLimit)

        // Must not throw — limit is inclusive of equal-size uploads.
        service.upload(multipart, ADVERTISER_ID)
        assertThat(storage.calls).hasSize(1)
    }

    @Test
    fun `MIME failure short-circuits before size check`() {
        // A 2 KB ZIP should report wrong MIME type (415-shaped) — closer to
        // what the client actually needs to fix — rather than 'too big'.
        val oversizeWrongType = ByteArray(2_000) { 0 }
        val multipart = MockMultipartFile(
            "file", "weird.zip", "application/zip", oversizeWrongType,
        )

        assertThrows<InvalidVideoMimeTypeException> { service.upload(multipart, ADVERTISER_ID) }
    }

    @Test
    fun `respects custom allowed MIME types from properties`() {
        // Reconfigure the service to allow MOV in addition to MP4.
        val customProps = properties.copy(
            allowedMimeTypes = listOf("video/mp4", "video/quicktime"),
        )
        val customService = VideoUploadService(storage, repo, customProps)

        val multipart = MockMultipartFile(
            "file", "demo.mov", "video/quicktime", "x".toByteArray(),
        )

        customService.upload(multipart, ADVERTISER_ID)
        assertThat(storage.calls).hasSize(1)
    }

    @Test
    fun `all upload exceptions extend the sealed VideoUploadException base`() {
        val empty = MockMultipartFile("file", "p.mp4", "video/mp4", ByteArray(0))
        val noName = MockMultipartFile("file", "", "video/mp4", "x".toByteArray())
        val badMime = MockMultipartFile("file", "p.mp4", "text/plain", "x".toByteArray())
        val tooBig = MockMultipartFile("file", "p.mp4", "video/mp4", ByteArray(1_001))

        val thrown = listOf(
            assertThrows<Throwable> { service.upload(empty, ADVERTISER_ID) },
            assertThrows<Throwable> { service.upload(noName, ADVERTISER_ID) },
            assertThrows<Throwable> { service.upload(badMime, ADVERTISER_ID) },
            assertThrows<Throwable> { service.upload(tooBig, ADVERTISER_ID) },
        )
        assertThat(thrown).allSatisfy { e ->
            assertThat(e).isInstanceOf(VideoUploadException::class.java)
        }
    }

    // ------------------------------------------------------------------
    // Hand-rolled storage fake — small surface, no Mockito overhead.
    // ------------------------------------------------------------------

    private class RecordingStorageService : VideoStoragePort {
        val calls = mutableListOf<MultipartFile>()

        override fun store(file: MultipartFile): StoredVideo {
            calls += file
            val name = "${UUID.randomUUID()}.mp4"
            return StoredVideo(
                filename = name,
                originalName = file.originalFilename ?: "unknown.mp4",
                mimeType = file.contentType ?: "video/mp4",
                sizeBytes = file.size,
                storagePath = "/fake/storage/$name",
                urlPath = "/api/videos/$name",
            )
        }
    }
}
