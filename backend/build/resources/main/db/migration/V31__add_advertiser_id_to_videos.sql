-- ===========================================================================
-- V31__add_advertiser_id_to_videos.sql
--
-- AC 4 — auth-and-isolation pass.
--
-- The original V30__create_videos.sql intentionally deferred the
-- per-advertiser ownership FK ("arrives in a later auth-and-isolation
-- sub-AC alongside the `Ad` entity"). This migration lands that FK directly
-- on `videos`: in this hackathon's data model the uploaded video *is* the
-- ad asset, so the ownership concept (`ad_advertiser_id` in the ontology)
-- maps to a column on `videos` rather than a separate `ads` table.
--
-- Effect:
--  - Adds `advertiser_id VARCHAR(36) NOT NULL`, FK → advertisers(id),
--    `ON DELETE CASCADE` so deleting an advertiser also tears down their
--    uploads (matching the pattern used by V90__device_assignments).
--  - Adds an index on `advertiser_id` to keep the
--    `findAllByAdvertiserIdOrderByUploadedAtDesc` query plan small even
--    once the videos table grows beyond the demo size.
--  - Adds a composite `(advertiser_id, uploaded_at)` index because the
--    canonical admin "my uploads, newest first" query filters by owner
--    *and* sorts by upload time — a single composite index lets the planner
--    serve both the WHERE and the ORDER BY without a sort step.
--
-- Backfill:
--  - This is a hackathon greenfield deployment; the videos table is empty
--    in every environment that runs Flyway (production hasn't seen its
--    first upload yet, and the integration tests use `ddl-auto=create-drop`
--    rather than Flyway). Adding a NOT NULL column with no default is
--    therefore safe and avoids leaving an "unowned video" loophole that
--    would defeat the data-isolation contract this AC exists to enforce.
--  - If a future migration is needed to backfill historical rows, it would
--    do so *before* this NOT NULL constraint is added — splitting into
--    two migrations (add nullable, backfill, alter to NOT NULL).
-- ===========================================================================

ALTER TABLE videos
    ADD COLUMN advertiser_id VARCHAR(36) NOT NULL;

ALTER TABLE videos
    ADD CONSTRAINT fk_videos_advertiser
        FOREIGN KEY (advertiser_id)
        REFERENCES advertisers (id)
        ON DELETE CASCADE;

-- Lookup: "list this advertiser's videos" — every authenticated GET
-- /api/videos call goes through this predicate.
CREATE INDEX idx_videos_advertiser_id ON videos (advertiser_id);

-- Hot path: admin "my uploads, newest first" combines the advertiser
-- predicate with the existing uploaded_at DESC sort.
CREATE INDEX idx_videos_advertiser_uploaded_at
    ON videos (advertiser_id, uploaded_at);
