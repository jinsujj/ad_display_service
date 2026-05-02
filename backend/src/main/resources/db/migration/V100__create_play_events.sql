-- ===========================================================================
-- V100__create_play_events.sql
--
-- AC 20202 Sub-AC 2 — server-side ad play telemetry.
--
-- Records every "ad started" / "ad finished" signal the Next.js player emits
-- as it rotates through the round-robin schedule on a device. The player
-- already enforces the daily cap client-side (web/lib/dailyCount.ts +
-- localStorage), but the server needs its own count so an operator's admin
-- dashboard can show authoritative cross-device telemetry — and so a
-- WebView whose localStorage was wiped (factory reset, app reinstall,
-- private mode) cannot silently double-spend an ad's daily cap.
--
-- Wire shape (POST /api/devices/{deviceId}/play-events):
--   {
--     "adId":      "<uuid>",
--     "eventType": "STARTED" | "FINISHED",
--     "occurredAt": "2026-05-02T11:21:13Z"   // optional, server stamps if missing
--   }
--
-- Storage model — append-only event log:
--   - One row per signal so we keep a full audit trail (start vs. finish lets
--     us reconstruct in-flight playbacks, dropped ads, watch-through rates).
--   - The row never mutates after insert — cheap to write, cheap to count.
--   - Aggregates (e.g. plays today per ad) are computed on read; for the
--     hackathon load this is a sub-millisecond `COUNT(*) WHERE …` against the
--     `(ad_id, event_type, occurred_at)` index.
--
-- Why **no foreign keys** on `ad_id` / `device_id`:
--   - Devices are identified by free-form UUIDs (no `devices` table exists in
--     the schema today; see V90 — the FK target was deferred). Adding an FK
--     here would require a `devices` parent migration that we explicitly
--     don't own.
--   - Ads CAN be deleted by an advertiser (the future `/api/ads` DELETE
--     endpoint cascades nothing onto an event log). We want the historical
--     telemetry to survive an ad deletion so reporting stays honest about
--     yesterday's traffic. A null/orphan FK + ON DELETE SET NULL would
--     satisfy that, but the simpler "no FK, just an indexed column" matches
--     the event-log pattern used by analytics tables elsewhere.
--   - The event-type CHECK keeps payload well-formed regardless.
--
-- Numbering: V100 lands well after V90 (device_assignments). No dependencies
-- on parent tables, so the migration applies cleanly even if a future V10
-- (devices) / V20 (restaurants) get backfilled later.
-- ===========================================================================

CREATE TABLE play_events (
    id            VARCHAR(36)  NOT NULL,
    device_id     VARCHAR(36)  NOT NULL,
    ad_id         VARCHAR(36)  NOT NULL,
    -- 'STARTED' fires on `<video onPlay>` (first paint of the new src — the
    -- WebView confirmed the byte-stream is decoding). 'FINISHED' fires on
    -- `<video onEnded>` after a complete playthrough; daily-cap accounting
    -- counts FINISHED rows specifically because operators want cap to mean
    -- "the screen actually played this all the way through".
    event_type    VARCHAR(16)  NOT NULL,
    -- Wall-clock timestamp the player believes the event occurred at. The
    -- player has its own clock (which may drift) — keep both this and the
    -- server-arrived `received_at` so a later reconciliation pass can
    -- detect / correct skew.
    occurred_at   TIMESTAMP    NOT NULL,
    -- Wall-clock timestamp the server received the request. Server-stamped
    -- so the demo dashboard can sort by "latest seen on the network" even
    -- when a device's clock is wildly off (cold-boot Android with no NTP).
    received_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_play_events PRIMARY KEY (id),
    -- Hardens the wire contract beyond the JSON DTO: even a hand-rolled
    -- INSERT (seed script, integration test) cannot land an unknown event
    -- type and silently corrupt the count queries.
    CONSTRAINT ck_play_events_type
        CHECK (event_type IN ('STARTED', 'FINISHED'))
);

-- Hot path: "how many FINISHED events for ad X today?" — daily cap query.
-- Composite covers both the WHERE (ad_id, event_type) and the ORDER BY /
-- range scan on occurred_at without a separate sort step in the planner.
CREATE INDEX idx_play_events_ad_event_time
    ON play_events (ad_id, event_type, occurred_at);

-- Lookup: "most recent activity from device Y" — debug + ops queries.
CREATE INDEX idx_play_events_device_received
    ON play_events (device_id, received_at);
