package me.owldev.adsignage.domain.ad

/**
 * 캠페인 기간(=상영 기간)을 기준으로 광고가 현재 어떤 라이프사이클 단계에
 * 있는지를 나타내는 계산값.
 *
 * - [SCHEDULED] : 오늘이 [Ad.campaignStartDate] 이전. 아직 송출 시작 전.
 * - [ACTIVE]   : 오늘이 [Ad.campaignStartDate]와 [Ad.campaignEndDate] 사이
 *                (양 끝 포함). 디바이스에 송출 중.
 * - [EXPIRED]  : 오늘이 [Ad.campaignEndDate] 이후. 자동으로 송출 중단됨
 *                (DB 상태는 그대로지만, 플레이리스트 계산이 제외).
 *
 * 별도의 `active` 컬럼을 두지 않는 이유: 시간 변경이 외부 요인이라(시계가
 * 흘러가면 자동으로 ACTIVE → EXPIRED 가 됨) 컬럼으로 보관하면 매일 자정에
 * 갱신해야 한다. 매번 계산하면 그 비동기성이 사라진다.
 *
 * 계산 로직은 [Ad.computeStatus] 인스턴스 메서드로 옮겨져 있다 — rich-domain
 * 원칙에 따라 도메인 룰이 자기 엔터티에 응집.
 */
enum class AdStatus { SCHEDULED, ACTIVE, EXPIRED }
