package me.owldev.adsignage.domain.video.upload

/**
 * Typed exceptions raised by [VideoUploadService] for invalid uploads.
 *
 * Sub-AC 3 explicitly calls for *typed* exceptions rather than the
 * `IllegalArgumentException` that the lower-level
 * [me.owldev.adsignage.domain.video.storage.VideoStorageService] uses for
 * filesystem-shape concerns. The split lets the API layer distinguish
 * "client sent us the wrong content type" (415) from "client sent us a file
 * that's too big" (413) without string-matching exception messages, and lets
 * the upload pipeline reject bad requests *before* writing any bytes to disk.
 *
 * All concrete subclasses extend the sealed [VideoUploadException] base so a
 * single `when` over `is VideoUploadException` can fan out into HTTP statuses
 * in [me.owldev.adsignage.web.GlobalExceptionHandler].
 */
sealed class VideoUploadException(message: String) : RuntimeException(message)

/**
 * The multipart upload had no bytes (or was missing entirely). Mapped to
 * HTTP 400 Bad Request.
 */
class EmptyVideoUploadException :
    VideoUploadException("Uploaded video is empty")

/**
 * The multipart upload arrived without a usable original filename — typically
 * an empty `filename` parameter or a path that reduces to nothing after
 * leaf-name normalisation. Mapped to HTTP 400 Bad Request.
 */
class MissingVideoFilenameException(val received: String?) :
    VideoUploadException(
        "Uploaded video has no usable original filename" +
            (received?.let { " (received: '$it')" } ?: ""),
    )

/**
 * The reported `Content-Type` is not in the configured allow-list (default
 * `video/mp4`). Mapped to HTTP 415 Unsupported Media Type.
 *
 *  - [actual]   the MIME type the client sent (or `null` if absent).
 *  - [allowed]  the set of MIME types the server is currently willing to
 *               accept; surfaced in the response so callers can self-correct.
 */
class InvalidVideoMimeTypeException(
    val actual: String?,
    val allowed: Collection<String>,
) : VideoUploadException(
    "Unsupported video MIME type: '${actual ?: "<missing>"}' (allowed: $allowed)",
)

/**
 * The upload exceeded the configured service-level size limit. Mapped to
 * HTTP 413 Payload Too Large.
 *
 *  - [actualBytes]  size reported by the multipart upload.
 *  - [maxBytes]     configured limit
 *                   (`adsignage.max-upload-size-bytes`, default 500 MiB).
 */
class VideoTooLargeException(
    val actualBytes: Long,
    val maxBytes: Long,
) : VideoUploadException(
    "Video size $actualBytes bytes exceeds the maximum allowed $maxBytes bytes",
)
