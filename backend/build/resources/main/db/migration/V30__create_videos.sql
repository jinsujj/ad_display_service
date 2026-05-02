-- ===========================================================================
-- V30__create_videos.sql
--
-- Stores metadata for uploaded video assets. The actual MP4 bytes live on the
-- server filesystem under `adsignage.video-storage-path` (default
-- /var/lib/adsignage/videos); this table records the indirection so the
-- streaming endpoint can resolve a filename → absolute path + content-type +
-- content-length without re-stat-ing the file on every Range request.
--
-- Numbering: slotted at V30 to leave room for V10=devices, V20=restaurants
-- (parent tables for V90__create_device_assignments). Videos are independent
-- of those parents — no FK out of this table — so V30 has no ordering
-- dependency beyond "after V1__advertisers".
-- ===========================================================================

CREATE TABLE videos (
    id            VARCHAR(36)   NOT NULL,
    filename      VARCHAR(255)  NOT NULL,
    original_name VARCHAR(255)  NOT NULL,
    mime_type     VARCHAR(100)  NOT NULL,
    size_bytes    BIGINT        NOT NULL,
    storage_path  VARCHAR(1024) NOT NULL,
    uploaded_at   TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_videos PRIMARY KEY (id),
    CONSTRAINT uk_videos_filename UNIQUE (filename)
);

-- Hot path: streaming endpoint resolves the on-disk filename from the URL
-- path variable. UNIQUE already covers this for equality lookups, but an
-- explicit index keeps the intent visible alongside the other domain tables.
CREATE INDEX idx_videos_filename ON videos (filename);

-- Admin "recent uploads" listing orders by uploaded_at DESC.
CREATE INDEX idx_videos_uploaded_at ON videos (uploaded_at);
