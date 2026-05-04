-- ===========================================================================
-- V104__add_role_to_advertisers.sql
--
-- RBAC: advertisers 테이블에 role 컬럼 추가.
--
-- 비즈니스 룰:
--   - ADVERTISER : 광고주(현재 모든 사용자가 default). 영상 업로드, 광고
--                  생성/스케줄, *자기 광고가* 어디 송출 중인지 read-only 조회.
--   - OPERATOR   : 플랫폼 운영자. 디바이스/음식점 관리, 광고 ↔ 디바이스
--                  큐 매칭, 모든 광고 모니터링.
--
-- 광고주가 임의로 특정 디바이스에 자기 광고를 끼워 넣지 못하도록 디바이스/
-- 큐 mutation 엔드포인트는 SecurityConfig 에서 OPERATOR 만 허용한다.
-- 기존 행은 모두 ADVERTISER 로 default — 운영자 계정은 별도 SQL UPDATE 로
-- 1개를 OPERATOR 로 승격해 부트스트랩.
-- ===========================================================================

ALTER TABLE advertisers
    ADD COLUMN role VARCHAR(32) NOT NULL DEFAULT 'ADVERTISER';

-- enum 값 무결성을 DB CHECK 로 강제 — application 레이어 우회로 들어와도
-- 잘못된 role 이 절대 영속화되지 않는다.
ALTER TABLE advertisers
    ADD CONSTRAINT ck_advertisers_role
    CHECK (role IN ('ADVERTISER', 'OPERATOR'));

CREATE INDEX idx_advertisers_role ON advertisers (role);
