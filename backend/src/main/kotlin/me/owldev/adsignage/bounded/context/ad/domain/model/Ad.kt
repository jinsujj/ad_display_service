package me.owldev.adsignage.bounded.context.ad.domain.model

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import me.owldev.adsignage.bounded.context.ad.domain.exception.InvalidScheduleException
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

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
    /* ----------------------------------------------------------- 도메인 룰
     * Rich-domain: 광고 자체가 자기 불변식을 강제. 서비스는 transaction +
     * 이벤트 발행만 책임지고, 어떤 변경이 유효한지는 Ad 가 결정한다.
     */

    /**
     * 일일 송출 윈도우와 일일 횟수를 한 번에 교체. 크로스 필드 룰
     * (`endTime > startTime`, `dailyPlayCount > 0`) 위반은
     * [InvalidScheduleException] 으로 즉시 거부한다 — DB CHECK 가
     * 최후의 가드로 남지만, 사용자에게는 깔끔한 field-error 응답이 가게.
     */
    fun changeSchedule(startTime: LocalTime, endTime: LocalTime, dailyPlayCount: Int) {
        validateTimeWindow(startTime, endTime)
        validateDailyPlayCount(dailyPlayCount)
        this.startTime = startTime
        this.endTime = endTime
        this.dailyPlayCount = dailyPlayCount
    }

    /**
     * 캠페인 기간을 교체. `end >= start` 강제.
     */
    fun changeCampaign(start: LocalDate, end: LocalDate) {
        validateCampaignWindow(start, end)
        this.campaignStartDate = start
        this.campaignEndDate = end
    }

    /**
     * 캠페인 기간 기준 라이프사이클 상태(SCHEDULED/ACTIVE/EXPIRED) 를 계산.
     * Clock 인자로 테스트가 시간을 고정 가능. 인스턴스 메서드 형태로 모이며,
     * 호출자는 `ad.computeStatus()` 같은 자연스러운 표현으로 사용 가능.
     */
    fun computeStatus(clock: Clock = Clock.systemDefaultZone()): AdStatus {
        val today = LocalDate.now(clock)
        return when {
            today.isBefore(campaignStartDate) -> AdStatus.SCHEDULED
            today.isAfter(campaignEndDate) -> AdStatus.EXPIRED
            else -> AdStatus.ACTIVE
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Ad) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()

    companion object {
        /**
         * 새 광고 인스턴스를 만들기 전에 모든 필드 룰을 검증하고 생성한다.
         * 컨트롤러/서비스가 직접 `Ad(...)` 생성자를 호출하는 대신 이 팩토리를
         * 거치면 어떤 경로로 만들어진 광고든 같은 룰을 통과한다.
         */
        fun create(
            advertiserId: String,
            title: String,
            videoFilename: String,
            startTime: LocalTime,
            endTime: LocalTime,
            dailyPlayCount: Int,
            campaignStartDate: LocalDate,
            campaignEndDate: LocalDate,
        ): Ad {
            validateTimeWindow(startTime, endTime)
            validateDailyPlayCount(dailyPlayCount)
            validateCampaignWindow(campaignStartDate, campaignEndDate)
            return Ad(
                advertiserId = advertiserId,
                title = title,
                videoFilename = videoFilename,
                startTime = startTime,
                endTime = endTime,
                dailyPlayCount = dailyPlayCount,
                campaignStartDate = campaignStartDate,
                campaignEndDate = campaignEndDate,
            )
        }

        private fun validateTimeWindow(start: LocalTime, end: LocalTime) {
            if (!end.isAfter(start)) {
                throw InvalidScheduleException(
                    fieldErrors = mapOf("endTime" to "endTime must be strictly after startTime"),
                )
            }
        }

        private fun validateDailyPlayCount(count: Int) {
            if (count <= 0) {
                throw InvalidScheduleException(
                    fieldErrors = mapOf("dailyPlayCount" to "dailyPlayCount must be positive"),
                )
            }
        }

        private fun validateCampaignWindow(start: LocalDate, end: LocalDate) {
            if (end.isBefore(start)) {
                throw InvalidScheduleException(
                    fieldErrors = mapOf("campaignEndDate" to "campaignEndDate must be on or after campaignStartDate"),
                )
            }
        }
    }
}
