package me.owldev.adsignage.domain.video

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * Stored uploaded video asset.
 *
 * Maps to the `videos` table created by Flyway migration V30.
 *
 * A `Video` is the *file-system-side* record of an MP4 that an advertiser
 * uploaded to the server. It is intentionally decoupled from the higher-level
 * `Ad` concept: the same video could in principle back multiple ads (and an
 * ad's lifecycle differs from the underlying file's lifecycle — soft-deleted
 * ads should not orphan the disk file mid-playback).
 *
 * Ontology concepts represented:
 *  - ad_video_filename → [filename] (stored on-disk, server-generated, unique)
 *  - ad_advertiser_id  → [advertiserId] (FK → advertisers.id, NOT NULL)
 *
 * Fields beyond the core ontology field are operational:
 *  - [originalName] preserves the advertiser-supplied filename for the admin UI.
 *  - [mimeType], [sizeBytes] support HTTP Range serving (Content-Type +
 *    Content-Length) without re-stat-ing the file on every request.
 *  - [storagePath] is the absolute on-disk path; resolved against the
 *    `adsignage.video-storage-path` config root at upload time and stored
 *    verbatim so the streaming endpoint never has to re-resolve it.
 *  - [uploadedAt] is captured at insert time for the admin "recent uploads"
 *    listing.
 *
 * Identity is the server-generated UUID — equality and hashCode are based on
 * [id] alone, matching the pattern set by `Advertiser` and `DeviceAssignment`.
 */
@Entity
@Table(name = "videos")
class Video(
    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    val id: String = UUID.randomUUID().toString(),

    /**
     * Owning advertiser's id (FK → `advertisers.id`).
     *
     * Set at upload time from the JWT principal — see
     * [me.owldev.adsignage.domain.video.upload.VideoUploadService.upload].
     * The data-isolation contract (AC 4) requires every read path that
     * surfaces a [Video] to a logged-in advertiser to filter by this
     * column, so the admin UI never sees another advertiser's uploads.
     *
     * Stored as a plain string FK rather than a `@ManyToOne Advertiser`
     * association to keep the entity flat and avoid lazy-init footguns
     * inside the controller — the controller only needs the id for
     * authorisation checks, never the full advertiser row.
     */
    @Column(name = "advertiser_id", nullable = false, updatable = false, length = 36)
    val advertiserId: String,

    /**
     * Server-generated filename used on disk. Unique across the table; the
     * upload pipeline derives this from the new UUID + extension to avoid
     * any collision with user-supplied names.
     */
    @Column(name = "filename", nullable = false, unique = true, length = 255)
    val filename: String,

    /**
     * Original filename as supplied by the advertiser's browser. Kept for
     * display in the admin UI; never used for filesystem operations.
     */
    @Column(name = "original_name", nullable = false, length = 255)
    val originalName: String,

    /**
     * MIME type as reported by the multipart upload (e.g. `video/mp4`).
     * Stored so the streaming endpoint can set `Content-Type` without
     * re-detecting on every request.
     */
    @Column(name = "mime_type", nullable = false, length = 100)
    val mimeType: String,

    /**
     * File size in bytes. Required for HTTP Range responses
     * (`Content-Range: bytes start-end/sizeBytes`).
     */
    @Column(name = "size_bytes", nullable = false)
    val sizeBytes: Long,

    /**
     * Absolute path on the server filesystem where the video is stored.
     * Resolved at upload time against `adsignage.video-storage-path`.
     */
    @Column(name = "storage_path", nullable = false, length = 1024)
    val storagePath: String,

    @Column(name = "uploaded_at", nullable = false, updatable = false)
    val uploadedAt: Instant = Instant.now(),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Video) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}
