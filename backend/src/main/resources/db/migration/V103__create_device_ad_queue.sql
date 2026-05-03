-- ===========================================================================
-- V103__create_device_ad_queue.sql
--
-- 디바이스 ↔ 광고 다대다 큐 테이블.
--
-- 기존 모델 한계:
--   "캠페인 기간 ACTIVE 인 모든 광고를 모든 매핑된 디바이스에 자동 송출"
--   → 운영자가 "어떤 디바이스에 어떤 광고를 송출할지" 결정할 방법이 없었다.
--
-- 새 모델:
--   - device-restaurant 매핑은 그대로 유지 (위치/식별용, 시점당 1:1).
--   - device-ad 큐 테이블이 별도로 존재 — 운영자가 디바이스마다 송출할
--     광고들을 *명시적으로* 담는다. 같은 광고가 여러 디바이스 큐에 들어갈
--     수 있고(다대다), 한 디바이스 큐에 여러 광고가 들어갈 수 있다.
--   - PlaylistController 는 "ACTIVE 인 모든 광고"가 아니라 *큐에 담긴 것 중*
--     ACTIVE 인 광고만 반환한다.
--
-- 멱등성:
--   (device_id, ad_id) UNIQUE — 같은 광고를 같은 큐에 두 번 담아도 중복
--   되지 않는다. 이미 있으면 ON CONFLICT 또는 application-level no-op.
-- ===========================================================================

CREATE TABLE device_ad_queue (
    device_id    VARCHAR(36)  NOT NULL,
    ad_id        VARCHAR(36)  NOT NULL,
    added_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_device_ad_queue PRIMARY KEY (device_id, ad_id)
);

CREATE INDEX idx_device_ad_queue_device_id ON device_ad_queue (device_id);
CREATE INDEX idx_device_ad_queue_ad_id     ON device_ad_queue (ad_id);
