package me.owldev.adsignage.domain.video.storage

import org.springframework.web.multipart.MultipartFile

/**
 * Persists uploaded video files to a backing store and returns the metadata
 * needed by the caller to build a [me.owldev.adsignage.domain.video.Video]
 * row and to expose the file via the streaming endpoint.
 *
 * The interface is intentionally backend-agnostic: the hackathon ships a
 * local-filesystem implementation ([LocalVideoStorageService]), but the same
 * contract could later be backed by S3 / GCS without touching the upload
 * controller or the [me.owldev.adsignage.domain.video.Video] entity.
 *
 * Sub-AC 2 contract:
 *  - Accepts a Spring [MultipartFile] (the natural input from the upload
 *    endpoint that this service will sit behind).
 *  - Generates a server-controlled [StoredVideo.filename] so that two
 *    advertisers uploading `promo.mp4` cannot collide on disk.
 *  - Returns the absolute [StoredVideo.storagePath] verbatim — the entity
 *    layer stores it without re-resolving against the config root, matching
 *    the intent documented in `Video.kt`.
 *  - Returns a [StoredVideo.urlPath] suitable for the player playlist; the
 *    streaming endpoint owns the actual route, this service just predicts
 *    the canonical URL shape (`/api/videos/{filename}`).
 */
interface VideoStorageService {

    /**
     * Persists [file] under the configured storage root.
     *
     * @throws IllegalArgumentException if the upload is empty, has no usable
     *   original filename, or has an unsupported extension.
     */
    fun store(file: MultipartFile): StoredVideo
}

/**
 * Result of a successful upload — every field a caller needs to construct a
 * [me.owldev.adsignage.domain.video.Video] entity *and* to advertise the
 * video on the player playlist.
 *
 *  - [filename]      server-generated, unique on disk; safe to expose in URLs.
 *  - [originalName]  sanitised advertiser-supplied filename for admin UI.
 *  - [mimeType]      Content-Type to serve from the streaming endpoint.
 *  - [sizeBytes]     measured *after* writing the file (authoritative).
 *  - [storagePath]   absolute on-disk path; opaque to the rest of the app.
 *  - [urlPath]       canonical streaming URL, e.g. `/api/videos/{filename}`.
 */
data class StoredVideo(
    val filename: String,
    val originalName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val storagePath: String,
    val urlPath: String,
)
