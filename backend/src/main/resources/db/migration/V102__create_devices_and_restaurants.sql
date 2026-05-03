-- ===========================================================================
-- V102__create_devices_and_restaurants.sql
--
-- devices, restaurants 테이블을 추가한다.
--
-- 두 테이블은 device_assignments(V90)이 FK 로 참조하지만 그동안 실제 DDL 이
-- 빠져 있었음 — 어드민의 GET /api/devices, /api/restaurants 가 실제 행을
-- 읽을 데가 없어 비어 있었다. 이번 마이그레이션이 그 공백을 채운다.
--
-- restaurants 는 데모용으로 3개 행을 함께 시드한다(강남/홍대/신촌). 이렇게
-- 해두면 광고주는 가입 직후 디바이스 매핑 데모를 곧바로 시연할 수 있다.
-- ===========================================================================

CREATE TABLE devices (
    device_id      VARCHAR(36)   NOT NULL,
    device_name    VARCHAR(255)  NOT NULL,
    registered_at  TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_seen_at   TIMESTAMP,

    CONSTRAINT pk_devices PRIMARY KEY (device_id)
);

CREATE INDEX idx_devices_registered_at ON devices (registered_at);

CREATE TABLE restaurants (
    restaurant_id  VARCHAR(36)   NOT NULL,
    name           VARCHAR(255)  NOT NULL,
    address        VARCHAR(255),
    created_at     TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_restaurants PRIMARY KEY (restaurant_id)
);

-- 데모 시드 데이터 — 광고주가 가입 직후 매핑 흐름을 시연할 수 있도록
INSERT INTO restaurants (restaurant_id, name, address) VALUES
  ('11111111-1111-1111-1111-111111111111', '강남 고깃집',     '서울특별시 강남구 테헤란로 123'),
  ('22222222-2222-2222-2222-222222222222', '홍대 횟집',       '서울특별시 마포구 어울마당로 45'),
  ('33333333-3333-3333-3333-333333333333', '신촌 국밥집',     '서울특별시 서대문구 연세로 88');
