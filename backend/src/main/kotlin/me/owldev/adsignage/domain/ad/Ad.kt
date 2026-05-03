package me.owldev.adsignage.domain.ad

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/**
 * 일일 재생 스케줄이 임베드된, 광고주가 소유한 광고 캠페인.
 *
 * Flyway 마이그레이션 V40이 생성한 `ads` 테이블에 매핑.
 *
 * [Ad]는 저장된 [me.owldev.adsignage.domain.video.Video](디스크 상의
 * [videoFilename]으로 참조 — 플레이어 URL과 플레이리스트가 이미 사용하는
 * 동일 식별자)를 스케줄 윈도우에 바인딩 — 일일 시작/종료 시각과 해당
 * 윈도우 내 목표 재생 횟수. 플레이리스트 엔드포인트는 이 테이블에서
 * 읽어 디바이스 플레이어가 SSE로 소비하는 라운드 로빈 스케줄을 생성함.
 *
 * 표현된 온톨로지 개념:
 *  - ad_id              → [id]
 *  - ad_advertiser_id   → [advertiserId]            (FK → advertisers.id)
 *  - ad_title           → [title]
 *  - ad_video_filename  → [videoFilename]           (FK → videos.filename)
 *  - schedule_start_time → [startTime]              (HH:mm 벽시계)
 *  - schedule_end_time   → [endTime]                (HH:mm 벽시계)
 *  - schedule_daily_count → [dailyPlayCount]        (목표 일일 재생 수)
 *
 * 스케줄 필드를 별도 `schedules` 테이블이 아닌 이 엔터티에 두는 이유:
 * 해커톤 PoC에서 각 광고는 정확히 하나의 스케줄을 가지므로 1:1 분리는
 * 현재 제품적 이점 없이 플레이리스트 핫패스의 JOIN을 두 배로 늘릴
 * 뿐임. 온톨로지는 *개념*(광고 vs 스케줄)을 구분할 뿐 저장소 레이아웃은
 * 구분하지 않음 — 임베딩은 광고 개념을 소유하는 동일 행에 모든 필요한
 * 스케줄 개념을 여전히 보존함.
 *
 * 시간 시맨틱:
 *  - [startTime] / [endTime]은 벽시계 일일 윈도우([Instant]가 아닌
 *    [LocalTime]) — 플레이어는 디바이스의 로컬 타임존에서 "지금이
 *    [start, end) 사이인가?"를 평가. `TIME`(날짜 없음)으로 저장하는 것이
 *    그 의도와 일치함.
 *  - DB 제약 `ck_ads_time_window`는 `endTime > startTime`을 강제;
 *    이를 위반하는 [Ad]를 쓰기 실패를 예상하지 않고 구성하지 말 것.
 *  - [dailyPlayCount]는 `> 0`이어야 함(DB 제약
 *    `ck_ads_daily_play_count_positive`).
 *
 * 식별자는 서버 생성 UUID — `Advertiser`, `DeviceAssignment`, `Video`가
 * 정한 패턴에 맞춰 [id]만으로 equality와 hashCode가 결정됨.
 */
@Entity
@Table(name = "ads")
class Ad(
    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    val id: String = UUID.randomUUID().toString(),

    /**
     * `advertisers.id`로의 FK. 이 광고를 소유한 광고주 — 생성 시 JWT
     * principal에서 채워짐. 리스팅 엔드포인트가 호출 광고주에게 광고 범위를
     * 좁히는 데 사용("auth_and_isolation" eval).
     */
    @Column(name = "advertiser_id", nullable = false, length = 36)
    val advertiserId: String,

    /**
     * 관리자 UI를 위한 표시 제목. 광고주가 재생성 없이 광고를 변경할 수
     * 있도록 가변(Mutable).
     */
    @Column(name = "title", nullable = false, length = 255)
    var title: String,

    /**
     * 뒷받침하는 MP4의 디스크 상 파일명(`videos.filename`으로의 FK).
     * 플레이리스트가 플레이어에게 이를 그대로 건네면 플레이어는
     * `https://stream.owl-dev.me/api/videos/{videoFilename}`을 빌드하여 HTTP
     * Range로 파일을 스트리밍. 광고주가 스케줄을 재생성하지 않고 기저
     * 비디오를 교체할 수 있도록 가변(Mutable).
     */
    @Column(name = "video_filename", nullable = false, length = 255)
    var videoFilename: String,

    /**
     * 일일 재생 윈도우 시작(벽시계, 디바이스 로컬 타임존).
     * 스케줄 필드 — 온톨로지의 `schedule_start_time`.
     */
    @Column(name = "start_time", nullable = false)
    var startTime: LocalTime,

    /**
     * 일일 재생 윈도우 종료(벽시계, 디바이스 로컬 타임존). [startTime]보다
     * 엄격히 뒤여야 함; `ck_ads_time_window`로 강제됨.
     * 스케줄 필드 — 온톨로지의 `schedule_end_time`.
     */
    @Column(name = "end_time", nullable = false)
    var endTime: LocalTime,

    /**
     * [startTime] .. [endTime] 윈도우 내 일일 목표 재생 수. `> 0`이어야 함
     * (`ck_ads_daily_play_count_positive`). 라운드 로빈 스케줄러가 활성
     * 디바이스 간에 이 목표를 분할.
     * 스케줄 필드 — 온톨로지의 `schedule_daily_count`.
     */
    @Column(name = "daily_play_count", nullable = false)
    var dailyPlayCount: Int,

    /**
     * 캠페인 시작일 (운영자 로컬 zone, 포함). V41에서 추가.
     * 오늘 날짜가 [campaignStartDate]와 [campaignEndDate] 사이에 있을 때만
     * 광고가 디바이스에 송출된다.
     */
    @Column(name = "campaign_start_date", nullable = false)
    var campaignStartDate: LocalDate,

    /**
     * 캠페인 종료일 (운영자 로컬 zone, 포함). [campaignStartDate] 이상이어야 함
     * (DB CHECK `ck_ads_campaign_window`).
     */
    @Column(name = "campaign_end_date", nullable = false)
    var campaignEndDate: LocalDate,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Ad) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}
