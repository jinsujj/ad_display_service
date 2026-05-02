package me.owldev.adsignage.domain.playevent

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * Discrete record of a single "ad started" or "ad finished" signal emitted
 * by the Next.js player as it rotates the schedule on a device.
 *
 * Backs the V100 `play_events` Flyway migration (an append-only event log;
 * never mutated after insert). Two reasons the server keeps its own count
 * even though `web/lib/dailyCount.ts` already enforces a per-device cap in
 * the player's `localStorage`:
 *
 *  1. Operators read the daily cap as a *campaign-wide* number — the
 *     advertiser bought 200 plays/day, not "200 plays per device per day".
 *     Aggregating per ad across every device that played it requires a
 *     server-side count.
 *  2. A WebView's `localStorage` is volatile (factory reset, app reinstall,
 *     private-mode profile). Without server-side telemetry a wiped device
 *     could silently start the day fresh and double-spend the cap.
 *
 * Storage model is a flat event log: one row per signal, two indexes (the
 * `(ad_id, event_type, occurred_at)` composite covers the daily-cap count
 * query exactly; the `(device_id, received_at)` index serves the
 * "what's that device been up to" debug query).
 *
 * No foreign keys — see migration V100 docstring for rationale (device IDs
 * are free-form UUIDs, ads can be deleted, telemetry must outlive both).
 */
@Entity
@Table(
    name = "play_events",
    indexes = [
        Index(
            name = "idx_play_events_ad_event_time",
            columnList = "ad_id, event_type, occurred_at",
        ),
        Index(
            name = "idx_play_events_device_received",
            columnList = "device_id, received_at",
        ),
    ],
)
class PlayEvent(
    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    val id: String = UUID.randomUUID().toString(),

    /**
     * UUID of the device that emitted the event — same id the player uses
     * in `/player/{deviceId}` and `/api/devices/{deviceId}/stream`.
     */
    @Column(name = "device_id", nullable = false, updatable = false, length = 36)
    val deviceId: String,

    /**
     * UUID of the ad that started/finished. Matches `ads.id` for live ads,
     * but we deliberately do **not** FK the column so an ad deletion
     * cannot orphan or cascade-erase historical telemetry.
     */
    @Column(name = "ad_id", nullable = false, updatable = false, length = 36)
    val adId: String,

    /**
     * Discriminator: STARTED or FINISHED. The DB CHECK constraint
     * `ck_play_events_type` mirrors this enum so a hand-rolled INSERT can
     * never land an unknown value.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, updatable = false, length = 16)
    val eventType: PlayEventType,

    /**
     * Wall-clock timestamp the player believes the event occurred at.
     * Player-supplied; trust-but-verify. Falls back to [receivedAt] if the
     * client omits it (see [PlayEventService.record]).
     */
    @Column(name = "occurred_at", nullable = false, updatable = false)
    val occurredAt: Instant,

    /**
     * Wall-clock timestamp the server stamped on receipt. Server-controlled
     * so a misconfigured device clock cannot reorder the event log.
     */
    @Column(name = "received_at", nullable = false, updatable = false)
    val receivedAt: Instant = Instant.now(),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PlayEvent) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}

/**
 * The two play-event signals the player emits, mirrored 1:1 by the
 * `ck_play_events_type` CHECK constraint and the wire contract on
 * `POST /api/devices/{deviceId}/play-events`.
 *
 * - STARTED: fired by the player's `<video onPlay>` handler — the
 *   WebView decoded the first frame of a new src and is now painting it.
 * - FINISHED: fired by `<video onEnded>` — a complete playthrough.
 *   Daily-cap accounting counts FINISHED rows specifically because
 *   operators expect the cap to reflect plays the screen actually
 *   completed, not aborted attempts.
 */
enum class PlayEventType {
    STARTED,
    FINISHED,
}
