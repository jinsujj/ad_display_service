package me.owldev.adsignage.domain.video.upload

import me.owldev.adsignage.domain.video.Video
import me.owldev.adsignage.domain.video.VideoRepository
import me.owldev.adsignage.domain.video.storage.StoredVideo
import me.owldev.adsignage.domain.video.storage.VideoStorageProperties
import me.owldev.adsignage.domain.video.storage.VideoStorageService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.multipart.MultipartFile
import java.util.Locale

/**
 * Domain entry-point for accepting an advertiser-uploaded video.
 *
 * Sub-AC 3 scope:
 *  - **MIME validation** — only [VideoStorageProperties.allowedMimeTypes]
 *    (default `["video/mp4"]`) are accepted; anything else (including a
 *    missing `Content-Type`) triggers [InvalidVideoMimeTypeException].
 *  - **Size enforcement** — uploads above
 *    [VideoStorageProperties.maxUploadSizeBytes] (default 500 MiB) trigger
 *    [VideoTooLargeException]. Both checks run against the multipart
 *    metadata *before* a single byte is written to disk so that pathological
 *    clients can't spam the server's filesystem.
 *  - **Typed exceptions** — every failure mode raises a subclass of the
 *    sealed [VideoUploadException]. The
 *    [me.owldev.adsignage.web.GlobalExceptionHandler] maps each subclass to
 *    its semantically correct HTTP status (400/413/415).
 *
 * Once validation passes the service delegates the actual filesystem write to
 * [VideoStorageService] (the local-disk implementation lives in
 * `LocalVideoStorageService`) and persists a [Video] row so the streaming
 * endpoint can resolve `filename → storage_path + content-type + size_bytes`
 * without re-stat-ing the file on every Range request.
 *
 * Why not fold this logic into [VideoStorageService]?
 *  - Storage talks about *where bytes go*; Upload talks about *whether bytes
 *    are allowed in the first place*. The split keeps `LocalVideoStorageService`
 *    reusable for non-HTTP imports (e.g. a future seed-data loader) where the
 *    HTTP-shaped MIME / size checks would be irrelevant.
 *  - It also lets the upload tests assert the *typed* exceptions without
 *    needing a `@TempDir` — the storage service is mocked so MIME/size
 *    rejection short-circuits before any I/O happens.
 */
@Service
class VideoUploadService(
    private val videoStorageService: VideoStorageService,
    private val videoRepository: VideoRepository,
    private val properties: VideoStorageProperties,
) {

    private val log = LoggerFactory.getLogger(VideoUploadService::class.java)

    /**
     * Lower-cased copy of the configured MIME whitelist; comparisons are
     * case-insensitive because some browsers report `Video/MP4` etc.
     */
    private val allowedMimeTypes: Set<String> =
        properties.allowedMimeTypes.map { it.lowercase(Locale.ROOT) }.toSet()

    /**
     * Validate, persist, and record an advertiser-supplied video upload.
     *
     * @param file the incoming multipart upload.
     * @param advertiserId the JWT-asserted owner id pulled from
     *   `@AuthenticationPrincipal AdvertiserPrincipal` in the controller.
     *   Stamping ownership at the service layer (rather than letting the
     *   controller construct a `Video` directly) means every uploader path
     *   — including future seed-data importers or admin re-uploads — is
     *   forced through the same ownership-required signature.
     *
     * @return the persisted [Video] entity (with server-assigned id, filename,
     *   storage path, re-stat-ed `sizeBytes`, and the supplied
     *   [advertiserId]).
     *
     * @throws EmptyVideoUploadException        when the multipart body has 0 bytes
     * @throws MissingVideoFilenameException    when the upload has no usable filename
     * @throws InvalidVideoMimeTypeException    when `Content-Type` is missing or unsupported
     * @throws VideoTooLargeException           when the upload exceeds the configured cap
     */
    @Transactional
    fun upload(file: MultipartFile, advertiserId: String): Video {
        require(advertiserId.isNotBlank()) { "advertiserId must not be blank" }

        validate(file)

        val stored: StoredVideo = videoStorageService.store(file)

        val video = Video(
            advertiserId = advertiserId,
            filename = stored.filename,
            originalName = stored.originalName,
            mimeType = stored.mimeType,
            sizeBytes = stored.sizeBytes,
            storagePath = stored.storagePath,
        )
        val saved = videoRepository.save(video)

        log.info(
            "video uploaded id={} filename={} advertiserId={} size={}B mime={}",
            saved.id, saved.filename, saved.advertiserId, saved.sizeBytes, saved.mimeType,
        )
        return saved
    }

    /**
     * Reject the upload up-front for any of the four invalid-input modes.
     *
     * Order matters: emptiness is the cheapest check, then filename presence,
     * then MIME (rejects the request without ever reading bytes), then size.
     * Ordering MIME before size also means a 1 GiB `application/zip` upload
     * is reported as "wrong type" rather than "too big" — closer to what the
     * client actually needs to fix.
     */
    private fun validate(file: MultipartFile) {
        if (file.isEmpty) {
            throw EmptyVideoUploadException()
        }

        val rawOriginal = file.originalFilename
        if (rawOriginal.isNullOrBlank()) {
            throw MissingVideoFilenameException(rawOriginal)
        }

        val mimeType = file.contentType?.trim()?.lowercase(Locale.ROOT)
        if (mimeType.isNullOrBlank() || mimeType !in allowedMimeTypes) {
            throw InvalidVideoMimeTypeException(
                actual = file.contentType,
                allowed = properties.allowedMimeTypes,
            )
        }

        // MultipartFile.size is the in-memory/on-disk byte count of the
        // already-buffered upload — authoritative for size limiting.
        val size = file.size
        if (size > properties.maxUploadSizeBytes) {
            throw VideoTooLargeException(
                actualBytes = size,
                maxBytes = properties.maxUploadSizeBytes,
            )
        }
    }
}
