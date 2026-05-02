package me.owldev.adsignage.domain.video.dto

import com.fasterxml.jackson.annotation.JsonInclude
import me.owldev.adsignage.domain.video.Video
import java.time.Instant

/**
 * Wire-level representation of a [Video] returned by `POST /api/videos`.
 *
 * Sub-AC 4 contract:
 *  - The controller must return a stable JSON shape independent of the JPA
 *    entity layout — Hibernate proxies and `@OneToMany` lazy collections
 *    have no business escaping the controller boundary.
 *  - Every field surfaced here corresponds to a column of the `videos` table
 *    that the admin UI / player playlist will need:
 *      - [id]           — server-generated UUID (primary key).
 *      - [filename]     — server-generated on-disk filename, also the path
 *                        segment of [url] that the player page will fetch
 *                        from the streaming endpoint.
 *      - [originalName] — advertiser-supplied filename, used by the admin
 *                        UI's "recent uploads" listing.
 *      - [mimeType]     — Content-Type (e.g. `video/mp4`); used by the
 *                        player's `<video>` element to pick a decoder.
 *      - [sizeBytes]    — re-stat-ed bytes-on-disk; useful for UI progress
 *                        indicators and Range request math.
 *      - [url]          — canonical streaming URL the player can fetch
 *                        without re-deriving the prefix; mirrors the
 *                        `urlPath` returned by the storage layer.
 *      - [uploadedAt]   — insert-time timestamp, used to sort the admin UI.
 *
 * `@JsonInclude(NON_NULL)` is defensive: the schema has no nullable columns
 * today, but if a future field is added as `String?` we don't want a stray
 * `"foo": null` polluting the contract.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class VideoResponse(
    val id: String,
    val filename: String,
    val originalName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val url: String,
    val uploadedAt: Instant,
) {
    companion object {
        /** Canonical URL prefix the streaming endpoint lives at. */
        private const val URL_PREFIX = "/api/videos"

        /**
         * Build the wire DTO from a persisted [Video]. The streaming URL is
         * derived from `filename` — the same shape `LocalVideoStorageService`
         * predicts in [me.owldev.adsignage.domain.video.storage.StoredVideo.urlPath]
         * — so the admin UI and the player page see one canonical URL form.
         */
        fun from(entity: Video): VideoResponse = VideoResponse(
            id = entity.id,
            filename = entity.filename,
            originalName = entity.originalName,
            mimeType = entity.mimeType,
            sizeBytes = entity.sizeBytes,
            url = "$URL_PREFIX/${entity.filename}",
            uploadedAt = entity.uploadedAt,
        )
    }
}
