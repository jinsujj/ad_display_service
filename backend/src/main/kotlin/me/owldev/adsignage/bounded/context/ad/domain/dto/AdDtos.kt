package me.owldev.adsignage.bounded.context.ad.domain.dto

import com.fasterxml.jackson.annotation.JsonFormat
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import me.owldev.adsignage.bounded.context.ad.domain.model.Ad
import me.owldev.adsignage.bounded.context.ad.domain.model.AdStatus
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * `POST /api/ads` 요청 본문.
 *
 * 광고 생성 = 영상 + 제목 + 일일 스케줄을 한 행에 묶는 동작.
 *  - [videoFilename]: 이미 업로드된 영상의 디스크 파일명 (Video.filename
 *    / VideoResponse.filename). 어드민 UI는 보통 `/videos` 목록에서 사용자가
 *    고른 영상의 filename을 그대로 채워 넣는다.
 *  - [title]/[startTime]/[endTime]/[dailyPlayCount] 는 [UpdateAdScheduleRequest]
 *    의 검증 규칙과 동일.
 */
data class CreateAdRequest(
    @field:NotBlank(message = "title must not be blank")
    @field:Size(max = 255, message = "title must be at most 255 characters")
    val title: String?,

    @field:NotBlank(message = "videoFilename must not be blank")
    @field:Size(max = 255, message = "videoFilename must be at most 255 characters")
    val videoFilename: String?,

    @field:NotNull(message = "startTime must not be null")
    @field:JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "HH:mm")
    val startTime: LocalTime?,

    @field:NotNull(message = "endTime must not be null")
    @field:JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "HH:mm")
    val endTime: LocalTime?,

    @field:NotNull(message = "dailyPlayCount must not be null")
    @field:Min(value = 1, message = "dailyPlayCount must be at least 1")
    @field:Max(value = 10_000, message = "dailyPlayCount must be at most 10000")
    val dailyPlayCount: Int?,

    @field:NotNull(message = "campaignStartDate must not be null")
    @field:JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    val campaignStartDate: LocalDate?,

    @field:NotNull(message = "campaignEndDate must not be null")
    @field:JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    val campaignEndDate: LocalDate?,
)

/**
 * Request body for `PUT /api/ads/{id}/schedule`.
 *
 * Carries the three schedule concepts from the ontology — start_time,
 * end_time, and daily_play_count — in a strongly-typed DTO so Bean
 * Validation can reject malformed payloads before they reach the service:
 *
 *  - `startTime` / `endTime` parse from `HH:mm` strings (configured via
 *    Jackson's [JsonFormat]). Out-of-range values like `25:00` raise
 *    `HttpMessageNotReadableException` — Jackson's parser fails before
 *    bean validation runs, which the global handler maps to 400.
 *  - `dailyPlayCount` is bounded `[1, 10000]`. The lower bound mirrors the
 *    DB CHECK constraint `ck_ads_daily_play_count_positive` (so the API
 *    matches the storage invariant); the upper bound is a sanity cap for
 *    the hackathon — at 1 second per video, 10000 plays/day is already
 *    >2.7 hours of unique playback per device, well past the demo's needs.
 *
 * Note: the cross-field rule "endTime > startTime" is **not** enforced here
 * — Bean Validation has no native multi-field constraint that maps cleanly
 * to a JSON field-error response. The service layer asserts it and raises
 * [me.owldev.adsignage.bounded.context.ad.domain.exception.InvalidScheduleException]
 * which the global handler renders identically to a single-field validation failure.
 */
data class UpdateAdScheduleRequest(
    @field:NotNull(message = "startTime must not be null")
    @field:JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "HH:mm")
    val startTime: LocalTime?,

    @field:NotNull(message = "endTime must not be null")
    @field:JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "HH:mm")
    val endTime: LocalTime?,

    @field:NotNull(message = "dailyPlayCount must not be null")
    @field:Min(value = 1, message = "dailyPlayCount must be at least 1")
    @field:Max(value = 10_000, message = "dailyPlayCount must be at most 10000")
    val dailyPlayCount: Int?,

    /**
     * 캠페인 시작/종료 날짜. 기존 호출자가 캠페인 윈도우는 건드리지 않는
     * 시나리오를 위해 둘 다 nullable — 두 값이 모두 제공된 경우에만 캠페인
     * 윈도우가 갱신되고, 누락 시 기존 값을 유지한다.
     */
    @field:JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    val campaignStartDate: LocalDate? = null,

    @field:JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    val campaignEndDate: LocalDate? = null,
)

/**
 * Response body for `PUT /api/ads/{id}/schedule` (and any future single-ad
 * read endpoint).
 *
 * Mirrors the persisted [Ad] as a stable wire contract — keeps Hibernate-
 * managed state out of the controller boundary and lets the JSON field
 * set evolve independently of the entity column layout. Times are
 * serialized as `HH:mm` to match the request shape so a client can round-
 * trip without re-formatting.
 */
data class AdResponse(
    val id: String,
    val advertiserId: String,
    val title: String,
    val videoFilename: String,
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "HH:mm")
    val startTime: LocalTime,
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "HH:mm")
    val endTime: LocalTime,
    val dailyPlayCount: Int,
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    val campaignStartDate: LocalDate,
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    val campaignEndDate: LocalDate,
    /** 응답 시점에 계산된 라이프사이클 상태 — 영속화되지 않음. */
    val status: AdStatus,
    val createdAt: Instant,
) {
    companion object {
        fun from(entity: Ad): AdResponse = AdResponse(
            id = entity.id,
            advertiserId = entity.advertiserId,
            title = entity.title,
            videoFilename = entity.videoFilename,
            startTime = entity.startTime,
            endTime = entity.endTime,
            dailyPlayCount = entity.dailyPlayCount,
            campaignStartDate = entity.campaignStartDate,
            campaignEndDate = entity.campaignEndDate,
            status = entity.computeStatus(),
            createdAt = entity.createdAt,
        )
    }
}

/**
 * 광고주가 자기 광고가 어떤 디바이스에 깔렸는지 read-only 로 보는 응답 항목.
 * `currentlyPlaying` 은 최근 5분 내 STARTED 이벤트가 그 광고에 대해 있는지로
 * 판단 — 디바이스가 그 순간 *그 광고* 를 화면에 띄우고 있다는 신호.
 *
 * 라이브 영상 미러용 필드:
 *  - [videoFilename] 은 부모 광고와 동일 — 프런트가 이미 알지만, 응답에
 *    자족(self-contained) 으로 담아 별도 lookup 없이 `<video src>` 만들도록.
 *  - [startedAt] 은 디바이스의 가장 최근 STARTED 발생 시각 (currentlyPlaying
 *    일 때만 채움). 모니터 영상이 디바이스 progress 와 시각 동기를 맞추는
 *    seek 계산에 사용.
 */
data class AdDeploymentItem(
    val deviceId: String,
    val deviceName: String,
    val restaurantName: String?,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val addedAt: Instant,
    val currentlyPlaying: Boolean,
    val videoFilename: String,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val startedAt: Instant?,
)
