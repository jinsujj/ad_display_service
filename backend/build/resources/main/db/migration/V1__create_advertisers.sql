-- V1: create advertisers table
-- Holds advertiser accounts that own ads and schedules.

CREATE TABLE advertisers (
    id            VARCHAR(36)  NOT NULL,
    email         VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_advertisers PRIMARY KEY (id),
    CONSTRAINT uk_advertisers_email UNIQUE (email)
);

CREATE INDEX idx_advertisers_email ON advertisers (email);
