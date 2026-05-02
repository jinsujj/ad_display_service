package me.owldev.adsignage.domain.video

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.Optional

/**
 * JPA repository for [Video].
 *
 * Lookup by [Video.filename] is needed by the streaming endpoint
 * (`GET /api/videos/{filename}`), which receives the on-disk filename as a
 * path variable rather than the UUID — keeps URLs short and lets the player
 * page reference videos via the same identifier the playlist hands it. The
 * streaming path stays public (no JWT) and intentionally does *not* filter
 * by advertiser — the player needs to fetch any advertiser's video that
 * appears in its assigned restaurant's playlist.
 *
 * [findAllByAdvertiserIdOrderByUploadedAtDesc] backs the admin "uploaded
 * videos" list view (`GET /api/videos`). The signed-in advertiser's id is
 * pulled from the JWT principal in the controller and pushed into the
 * predicate so the query plan uses the
 * `(advertiser_id, uploaded_at)` composite index added in V31 — both the
 * WHERE and ORDER BY are served without a sort step. This is the AC 4
 * data-isolation contract on the repository side.
 *
 * [findByIdAndAdvertiserId] is the canonical "fetch this single video, but
 * only if it belongs to me" lookup. Future single-video read or mutate
 * endpoints (rename, delete, schedule-attach) will go through this method
 * so cross-advertiser id guessing returns `Optional.empty()` rather than
 * an arbitrary advertiser's row.
 */
@Repository
interface VideoRepository : JpaRepository<Video, String> {
    fun findByFilename(filename: String): Optional<Video>
    fun existsByFilename(filename: String): Boolean
    fun findAllByAdvertiserIdOrderByUploadedAtDesc(advertiserId: String): List<Video>
    fun findByIdAndAdvertiserId(id: String, advertiserId: String): Optional<Video>
}
