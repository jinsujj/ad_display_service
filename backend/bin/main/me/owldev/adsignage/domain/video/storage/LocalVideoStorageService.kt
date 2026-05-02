package me.owldev.adsignage.domain.video.storage

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.UUID

/**
 * Local-filesystem implementation of [VideoStorageService].
 *
 * Reads the storage root from [VideoStorageProperties.videoStoragePath]
 * (default `/var/lib/adsignage/videos`, configurable per-environment) and
 * streams uploads directly to disk via [Files.copy], so even multi-hundred-MB
 * MP4s are not buffered in memory.
 *
 * Filename strategy: `{UUID}.{ext}` where `ext` is taken from the advertiser's
 * original filename, lower-cased, and validated against [ALLOWED_EXTENSIONS].
 * The UUID prefix guarantees on-disk uniqueness without trusting the client.
 *
 * Path-traversal hardening:
 *  - `originalName` is reduced to its leaf segment (anything resembling
 *    `../../etc/foo.mp4` becomes `foo.mp4`) before any other processing.
 *  - The computed target path is normalised and re-checked to ensure it
 *    still lies under the configured [rootDir], rejecting any pathological
 *    UUID expansion (unreachable in practice, but cheap insurance).
 *
 * The service does *not* persist a [me.owldev.adsignage.domain.video.Video]
 * row — that's the caller's responsibility (typically a `VideoUploadService`
 * in a follow-up Sub-AC). Keeping persistence and storage separate lets the
 * H2/Postgres entity layer stay testable without touching the disk, and lets
 * this service be reused if we ever need to import an external video file.
 */
@Service
class LocalVideoStorageService(
    private val properties: VideoStorageProperties,
) : VideoStorageService {

    private val log = LoggerFactory.getLogger(LocalVideoStorageService::class.java)

    /**
     * Absolute, normalised storage root. Computed once at construction time
     * because the property is immutable for the lifetime of the application.
     * Tests inject a `@TempDir`; production points at `/var/lib/adsignage/videos`.
     */
    private val rootDir: Path = Paths.get(properties.videoStoragePath)
        .toAbsolutePath()
        .normalize()

    override fun store(file: MultipartFile): StoredVideo {
        require(!file.isEmpty) { "Uploaded video is empty" }

        val rawOriginal = file.originalFilename
            ?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("Uploaded video has no original filename")

        // Reduce to leaf name to defeat `../../etc/foo.mp4` style inputs before
        // we even look at the extension.
        val safeOriginal = Paths.get(rawOriginal).fileName?.toString()
            ?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("Uploaded video has no usable filename: $rawOriginal")

        val extension = safeOriginal
            .substringAfterLast('.', missingDelimiterValue = "")
            .lowercase(Locale.ROOT)
        require(extension.isNotBlank() && extension in ALLOWED_EXTENSIONS) {
            "Unsupported video extension: '$extension' (allowed: $ALLOWED_EXTENSIONS)"
        }

        // Idempotent: createDirectories is a no-op if rootDir already exists.
        Files.createDirectories(rootDir)

        val storedFilename = "${UUID.randomUUID()}.$extension"
        val target = rootDir.resolve(storedFilename).normalize()
        check(target.startsWith(rootDir)) {
            "Resolved storage path escaped the configured root: $target"
        }

        file.inputStream.use { input ->
            Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING)
        }

        // Re-stat the file rather than trusting MultipartFile.size — Spring's
        // value can be -1 in edge cases, and the on-disk truth is what the
        // streaming endpoint will serve via Content-Length anyway.
        val sizeBytes = Files.size(target)
        val mimeType = file.contentType
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_MIME_TYPE

        log.info(
            "video stored filename={} size={}B mime={} path={}",
            storedFilename, sizeBytes, mimeType, target,
        )

        return StoredVideo(
            filename = storedFilename,
            originalName = safeOriginal,
            mimeType = mimeType,
            sizeBytes = sizeBytes,
            storagePath = target.toString(),
            urlPath = "$URL_PREFIX/$storedFilename",
        )
    }

    companion object {
        /**
         * Whitelist of accepted video extensions. MP4 is the demo target; the
         * others are common WebView-friendly containers we may want to accept
         * during testing without needing a config flag.
         */
        private val ALLOWED_EXTENSIONS = setOf("mp4", "webm", "mov", "m4v")

        /**
         * Fallback Content-Type used when the multipart upload omits one.
         * MP4 is the dominant case for this service so this is a safe default.
         */
        private const val DEFAULT_MIME_TYPE = "video/mp4"

        /**
         * Canonical URL prefix the streaming endpoint will live at. Predicted
         * here so the playlist payload can be assembled without round-tripping
         * through the controller. If the route ever moves, update both sides.
         */
        private const val URL_PREFIX = "/api/videos"
    }
}
