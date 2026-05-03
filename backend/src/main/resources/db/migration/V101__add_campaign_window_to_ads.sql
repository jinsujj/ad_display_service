-- ===========================================================================
-- V101__add_campaign_window_to_ads.sql
--
-- ads 테이블에 캠페인 기간(상영 기간) 두 컬럼을 추가한다.
--
--   campaign_start_date  — 광고 송출 시작 날짜 (DATE, NOT NULL)
--   campaign_end_date    — 광고 송출 종료 날짜 (DATE, NOT NULL, >= start)
--
-- 일일 시간 윈도우(start_time / end_time / daily_play_count)는 매일 반복되는
-- 벽시계 윈도우인 반면, 캠페인 기간은 광고가 *유효한 날짜 범위* 자체를
-- 정의한다. 두 차원이 모두 만족되어야 광고가 디바이스에 송출된다.
--
-- 만료 처리 전략:
--   별도 cron/스케줄러를 두지 않고, 플레이리스트 계산 시점에
--   `today BETWEEN campaign_start_date AND campaign_end_date` 술어로
--   필터링한다. 즉 "자동 취소"는 시간이 지나면 *자연스럽게* 일어나는
--   계산의 결과이고, DB의 행을 지우거나 플래그를 토글하지 않는다.
--   덕분에 운영자는 캠페인 기간을 늘려 즉시 다시 송출하게 만들 수 있고,
--   별도의 활성/비활성 상태 동기화 버그가 생기지 않는다.
--
-- 백필:
--   기존 행이 즉시 만료되면 데모 흐름이 깨지므로,
--     - campaign_start_date = CURRENT_DATE
--     - campaign_end_date   = '2099-12-31' (사실상 영구)
--   로 채운 다음 NOT NULL 제약을 부여한다. 운영자는 어드민에서 의미 있는
--   값으로 갱신할 수 있다.
-- ===========================================================================

ALTER TABLE ads ADD COLUMN campaign_start_date DATE;
ALTER TABLE ads ADD COLUMN campaign_end_date   DATE;

UPDATE ads SET campaign_start_date = CURRENT_DATE WHERE campaign_start_date IS NULL;
UPDATE ads SET campaign_end_date   = DATE '2099-12-31' WHERE campaign_end_date IS NULL;

ALTER TABLE ads ALTER COLUMN campaign_start_date SET NOT NULL;
ALTER TABLE ads ALTER COLUMN campaign_end_date   SET NOT NULL;

ALTER TABLE ads
    ADD CONSTRAINT ck_ads_campaign_window
    CHECK (campaign_end_date >= campaign_start_date);

CREATE INDEX idx_ads_campaign_end_date ON ads (campaign_end_date);
