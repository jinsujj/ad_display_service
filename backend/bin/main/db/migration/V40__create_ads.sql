-- ===========================================================================
-- V40__create_ads.sql
--
-- Stores advertiser-owned ad campaigns. Each ad binds an uploaded video asset
-- (referenced by `videos.filename` — the on-disk identifier the player URL
-- already speaks) to a daily playback schedule (start_time .. end_time, with
-- a per-day play count). The playlist endpoint reads from this table to build
-- the round-robin schedule the player consumes via SSE.
--
-- Schedule fields (start_time, end_time, daily_play_count) live directly on
-- the ads table rather than in a separate `schedules` table. Rationale for
-- the hackathon PoC:
--
--  - Each ad has exactly one schedule today; a 1:1 split would just double
--    the JOINs on the playlist hot path with no current product benefit.
--  - The ontology distinguishes the *concepts* (ad vs. schedule), not the
--    storage layout — embedding still preserves every required concept
--    (schedule_start_time, schedule_end_time, schedule_daily_count) on the
--    same row that owns ad_id / ad_title / ad_video_filename / ad_advertiser_id.
--  - If a future iteration needs N schedules per ad, splitting then is
--    mechanical (move the three columns to a child table + FK).
--
-- Numbering: slotted at V40 to fit between V30=videos (referenced by
-- video_filename) and V90=device_assignments. Depends on V1=advertisers
-- (FK target) and V30=videos (referenced filename); both apply earlier.
-- ===========================================================================

CREATE TABLE ads (
    id                VARCHAR(36)  NOT NULL,
    advertiser_id     VARCHAR(36)  NOT NULL,
    title             VARCHAR(255) NOT NULL,
    video_filename    VARCHAR(255) NOT NULL,
    -- Schedule fields. start_time / end_time are wall-clock daily windows
    -- (HH:mm:ss) — the player evaluates "is now within [start, end)?" in the
    -- device's local timezone; storing as TIME (no date) matches that intent.
    -- daily_play_count is the *target* number of plays per day inside the
    -- window; the round-robin scheduler divides that across active devices.
    start_time        TIME         NOT NULL,
    end_time          TIME         NOT NULL,
    daily_play_count  INTEGER      NOT NULL,
    created_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_ads PRIMARY KEY (id),
    CONSTRAINT fk_ads_advertiser
        FOREIGN KEY (advertiser_id)
        REFERENCES advertisers (id)
        ON DELETE CASCADE,
    CONSTRAINT fk_ads_video_filename
        FOREIGN KEY (video_filename)
        REFERENCES videos (filename)
        ON DELETE RESTRICT,
    -- Schedule-window sanity: end strictly after start. The admin UI also
    -- enforces this client-side; the DB constraint guards direct writes
    -- (e.g. seed scripts) from corrupting the playlist computation.
    CONSTRAINT ck_ads_time_window CHECK (end_time > start_time),
    CONSTRAINT ck_ads_daily_play_count_positive CHECK (daily_play_count > 0)
);

-- Hot path: "list all ads owned by advertiser X" (admin dashboard).
CREATE INDEX idx_ads_advertiser_id ON ads (advertiser_id);

-- Hot path: playlist computation joins ads → videos by filename.
CREATE INDEX idx_ads_video_filename ON ads (video_filename);

-- Admin "recent ads" listing orders by created_at DESC.
CREATE INDEX idx_ads_created_at ON ads (created_at);
