package me.owldev.adsignage.domain.video.storage

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Type-safe binding for the on-disk video storage root and upload validation
 * thresholds.
 *
 * Maps `adsignage.*` from `application.yml` (overridable via env vars).
 * Spring's relaxed binding maps the kebab-case keys to their camelCase
 * counterparts here.
 *
 *  - [videoStoragePath] — absolute filesystem path under which uploaded MP4
 *    bytes live. Defaults to `/var/lib/adsignage/videos` to match the
 *    production deploy layout. Tests inject a temp directory.
 *
 *  - [maxUploadSizeBytes] — Sub-AC 3 service-level size cap, evaluated
 *    *before* we touch the disk. Independent of Spring's
 *    `spring.servlet.multipart.max-file-size`: that one bounces requests at
 *    the parser and returns Spring's own `MaxUploadSizeExceededException`,
 *    while this one yields the typed [me.owldev.adsignage.domain.video.upload.VideoTooLargeException]
 *    so the API contract can speak in domain terms (and so we keep the same
 *    behaviour even if the multipart parser is reconfigured later).
 *    Defaults to 500 MiB to match the multipart limit in `application.yml`.
 *
 *  - [allowedMimeTypes] — Sub-AC 3 strict MIME whitelist. Per the seed
 *    contract the demo target is `video/mp4`; we keep this as a list (not a
 *    single value) so that test fixtures or demo footage encoded as
 *    `video/quicktime` can be opt-in via configuration without code changes.
 *    The default is the single canonical type.
 */
@ConfigurationProperties(prefix = "adsignage")
data class VideoStorageProperties(
    val videoStoragePath: String = "/var/lib/adsignage/videos",
    val maxUploadSizeBytes: Long = DEFAULT_MAX_UPLOAD_SIZE_BYTES,
    val allowedMimeTypes: List<String> = listOf("video/mp4"),
) {
    companion object {
        /** 500 MiB, matching `spring.servlet.multipart.max-file-size`. */
        const val DEFAULT_MAX_UPLOAD_SIZE_BYTES: Long = 500L * 1024 * 1024
    }
}
