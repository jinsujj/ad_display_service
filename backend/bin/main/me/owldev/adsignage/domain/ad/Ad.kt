package me.owldev.adsignage.domain.ad

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.time.LocalTime
import java.util.UUID

/**
 * Advertiser-owned ad campaign with an embedded daily playback schedule.
 *
 * Maps to the `ads` table created by Flyway migration V40.
 *
 * An [Ad] binds a stored [me.owldev.adsignage.domain.video.Video] (referenced
 * by its on-disk [videoFilename] — the same identifier the player URL and
 * playlist already speak) to a schedule window — daily start/end clock times
 * plus the target number of plays inside that window. The playlist endpoint
 * reads from this table to produce the round-robin schedule the device player
 * consumes via SSE.
 *
 * Ontology concepts represented:
 *  - ad_id              → [id]
 *  - ad_advertiser_id   → [advertiserId]            (FK → advertisers.id)
 *  - ad_title           → [title]
 *  - ad_video_filename  → [videoFilename]           (FK → videos.filename)
 *  - schedule_start_time → [startTime]              (HH:mm wall clock)
 *  - schedule_end_time   → [endTime]                (HH:mm wall clock)
 *  - schedule_daily_count → [dailyPlayCount]        (target plays/day)
 *
 * Why schedule fields live on this entity rather than a separate `schedules`
 * table: each ad has exactly one schedule for the hackathon PoC, so a 1:1
 * split would double the JOINs on the playlist hot path with no current
 * product benefit. The ontology distinguishes the *concepts* (ad vs.
 * schedule), not the storage layout — embedding still preserves every
 * required schedule concept on the same row that owns the ad concepts.
 *
 * Time semantics:
 *  - [startTime] / [endTime] are wall-clock daily windows ([LocalTime], not
 *    [Instant]) — the player evaluates "is now within [start, end)?" in the
 *    device's local timezone. Storing as `TIME` (no date) matches that intent.
 *  - The DB constraint `ck_ads_time_window` enforces `endTime > startTime`;
 *    don't construct an [Ad] that violates this without expecting a write
 *    failure.
 *  - [dailyPlayCount] must be `> 0` (DB constraint `ck_ads_daily_play_count_positive`).
 *
 * Identity is the server-generated UUID — equality and hashCode are based on
 * [id] alone, matching the pattern set by `Advertiser`, `DeviceAssignment`,
 * and `Video`.
 */
@Entity
@Table(name = "ads")
class Ad(
    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    val id: String = UUID.randomUUID().toString(),

    /**
     * FK to `advertisers.id`. The advertiser that owns this ad — populated
     * from the JWT principal at create time. Used by the listing endpoint to
     * scope ads to the calling advertiser ("auth_and_isolation" eval).
     */
    @Column(name = "advertiser_id", nullable = false, length = 36)
    val advertiserId: String,

    /**
     * Display title for the admin UI. Mutable so an advertiser can rename an
     * ad without recreating it.
     */
    @Column(name = "title", nullable = false, length = 255)
    var title: String,

    /**
     * On-disk filename of the backing MP4 (FK to `videos.filename`). The
     * playlist hands this to the player verbatim, which builds
     * `https://stream.owl-dev.me/api/videos/{videoFilename}` to stream the
     * file via HTTP Range. Mutable so an advertiser can swap the underlying
     * video without recreating the schedule.
     */
    @Column(name = "video_filename", nullable = false, length = 255)
    var videoFilename: String,

    /**
     * Daily playback window start (wall clock, device-local timezone).
     * Schedule field — `schedule_start_time` in the ontology.
     */
    @Column(name = "start_time", nullable = false)
    var startTime: LocalTime,

    /**
     * Daily playback window end (wall clock, device-local timezone). Must
     * be strictly after [startTime]; enforced by `ck_ads_time_window`.
     * Schedule field — `schedule_end_time` in the ontology.
     */
    @Column(name = "end_time", nullable = false)
    var endTime: LocalTime,

    /**
     * Target number of plays per day within the [startTime] .. [endTime]
     * window. Must be `> 0` (`ck_ads_daily_play_count_positive`). The
     * round-robin scheduler divides this target across active devices.
     * Schedule field — `schedule_daily_count` in the ontology.
     */
    @Column(name = "daily_play_count", nullable = false)
    var dailyPlayCount: Int,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Ad) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}
