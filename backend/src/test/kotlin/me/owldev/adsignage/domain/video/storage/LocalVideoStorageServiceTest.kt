package me.owldev.adsignage.domain.video.storage

import me.owldev.adsignage.bounded.context.video.adapter.out.database.VideoRepository
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
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.springframework.mock.web.MockMultipartFile
import java.nio.file.Files
import java.nio.file.Path

/**
 * Unit tests for [LocalVideoStorageAdapter].
 *
 * Plain JUnit + JUnit's `@TempDir` so we can verify the on-disk side effects
 * without booting Spring. Aligns with the project's existing pattern of
 * favouring hand-rolled fakes over Mockito (see `DeviceAssignmentServiceTest`).
 */
class LocalVideoStorageServiceTest {

    @TempDir
    lateinit var tempDir: Path

    private fun service(root: Path = tempDir): LocalVideoStorageAdapter =
        LocalVideoStorageAdapter(VideoStorageProperties(videoStoragePath = root.toString()))

    @Test
    fun `stores uploaded mp4 under the configured root with a UUID filename`() {
        val bytes = "fake-mp4-bytes".toByteArray()
        val multipart = MockMultipartFile(
            /* name = */ "file",
            /* originalFilename = */ "promo.mp4",
            /* contentType = */ "video/mp4",
            bytes,
        )

        val result = service().store(multipart)

        assertThat(result.originalName).isEqualTo("promo.mp4")
        assertThat(result.mimeType).isEqualTo("video/mp4")
        assertThat(result.sizeBytes).isEqualTo(bytes.size.toLong())
        assertThat(result.filename).matches(Regex("[0-9a-f-]{36}\\.mp4").toPattern())
        assertThat(result.urlPath).isEqualTo("/api/videos/${result.filename}")

        val stored = Path.of(result.storagePath)
        assertThat(stored).isRegularFile()
        assertThat(Files.readAllBytes(stored)).isEqualTo(bytes)
        assertThat(stored.parent).isEqualTo(tempDir.toAbsolutePath().normalize())
    }

    @Test
    fun `creates the storage directory if it does not yet exist`() {
        val nested = tempDir.resolve("level1/level2")
        // Sanity: directory must not exist before the first upload.
        assertThat(Files.exists(nested)).isFalse()

        val multipart = MockMultipartFile("file", "ad.mp4", "video/mp4", "x".toByteArray())
        val result = service(root = nested).store(multipart)

        assertThat(nested).isDirectory()
        assertThat(Path.of(result.storagePath)).isRegularFile()
    }

    @Test
    fun `each upload of the same original filename gets a unique stored filename`() {
        val svc = service()
        val a = MockMultipartFile("file", "ad.mp4", "video/mp4", "a".toByteArray())
        val b = MockMultipartFile("file", "ad.mp4", "video/mp4", "b".toByteArray())

        val first = svc.store(a)
        val second = svc.store(b)

        assertThat(first.filename).isNotEqualTo(second.filename)
        assertThat(Files.readAllBytes(Path.of(first.storagePath))).isEqualTo("a".toByteArray())
        assertThat(Files.readAllBytes(Path.of(second.storagePath))).isEqualTo("b".toByteArray())
    }

    @Test
    fun `path traversal in the original filename is reduced to the leaf name`() {
        val multipart = MockMultipartFile(
            "file",
            "../../etc/secret.mp4",
            "video/mp4",
            "x".toByteArray(),
        )

        val result = service().store(multipart)

        assertThat(result.originalName).isEqualTo("secret.mp4")
        // Stored file must still live directly under the configured root.
        assertThat(Path.of(result.storagePath).parent)
            .isEqualTo(tempDir.toAbsolutePath().normalize())
    }

    @Test
    fun `falls back to video mp4 when contentType is missing`() {
        val multipart = MockMultipartFile(
            "file",
            "ad.mp4",
            /* contentType = */ null,
            "x".toByteArray(),
        )

        val result = service().store(multipart)

        assertThat(result.mimeType).isEqualTo("video/mp4")
    }

    @Test
    fun `rejects empty uploads`() {
        val multipart = MockMultipartFile("file", "ad.mp4", "video/mp4", ByteArray(0))

        assertThrows<IllegalArgumentException> { service().store(multipart) }
    }

    @Test
    fun `rejects upload with no original filename`() {
        val multipart = MockMultipartFile("file", "", "video/mp4", "x".toByteArray())

        assertThrows<IllegalArgumentException> { service().store(multipart) }
    }

    @Test
    fun `rejects unsupported extensions`() {
        val multipart = MockMultipartFile(
            "file",
            "ad.txt",
            "text/plain",
            "x".toByteArray(),
        )

        assertThrows<IllegalArgumentException> { service().store(multipart) }
    }

    @Test
    fun `rejects file with no extension`() {
        val multipart = MockMultipartFile(
            "file",
            "no-extension",
            "video/mp4",
            "x".toByteArray(),
        )

        assertThrows<IllegalArgumentException> { service().store(multipart) }
    }

    @Test
    fun `accepts mixed-case extensions`() {
        val multipart = MockMultipartFile(
            "file",
            "PROMO.MP4",
            "video/mp4",
            "x".toByteArray(),
        )

        val result = service().store(multipart)

        // Stored filename always has the lower-cased extension; the original
        // (case-preserving) name is kept verbatim for the admin UI.
        assertThat(result.filename).endsWith(".mp4")
        assertThat(result.originalName).isEqualTo("PROMO.MP4")
    }
}
