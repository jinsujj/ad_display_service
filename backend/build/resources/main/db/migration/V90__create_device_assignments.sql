-- ===========================================================================
-- V90__create_device_assignments.sql
--
-- Maps a physical signage device to a restaurant at a point in time.
-- The (active = TRUE) row for a given device_id represents the current
-- assignment; deactivated rows remain as audit history of past mappings.
--
-- Dependencies:
--   - devices(device_id)         (created by an earlier migration, e.g. V10)
--   - restaurants(restaurant_id) (created by an earlier migration, e.g. V20)
--
-- This file is intentionally numbered V90 so Flyway runs it after the parent
-- tables exist; renumber if the project converges on a different ordering.
-- ===========================================================================

CREATE TABLE device_assignments (
    id              VARCHAR(36)  NOT NULL,
    device_id       VARCHAR(36)  NOT NULL,
    restaurant_id   VARCHAR(36)  NOT NULL,
    assigned_at     TIMESTAMP    NOT NULL,
    active          BOOLEAN      NOT NULL DEFAULT TRUE,
    CONSTRAINT pk_device_assignments
        PRIMARY KEY (id),
    CONSTRAINT fk_device_assignments_device
        FOREIGN KEY (device_id)
        REFERENCES devices (device_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_device_assignments_restaurant
        FOREIGN KEY (restaurant_id)
        REFERENCES restaurants (restaurant_id)
        ON DELETE CASCADE
);

-- Lookup: "what is device X assigned to right now?" — covered by composite
CREATE INDEX idx_device_assignments_device_id
    ON device_assignments (device_id);

-- Lookup: "which devices are at restaurant Y?"
CREATE INDEX idx_device_assignments_restaurant_id
    ON device_assignments (restaurant_id);

-- Filter helper for the common "active assignments only" query path
CREATE INDEX idx_device_assignments_active
    ON device_assignments (active);

-- Hot path: resolving the current assignment for a device when building
-- the playlist or handling SSE remap events.
CREATE INDEX idx_device_assignments_device_active
    ON device_assignments (device_id, active);
