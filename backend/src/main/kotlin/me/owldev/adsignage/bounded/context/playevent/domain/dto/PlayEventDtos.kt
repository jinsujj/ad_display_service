package me.owldev.adsignage.bounded.context.playevent.domain.dto

import com.fasterxml.jackson.annotation.JsonFormat
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import me.owldev.adsignage.bounded.context.playevent.domain.model.PlayEvent
import me.owldev.adsignage.bounded.context.playevent.domain.model.PlayEventType
import java.time.Instant

/**
 * `POST /api/devices/{deviceId}/play-events` 의 요청 본문.
 *
 * 와이어 형태:
 * ```json
 * {
 *   "adId":       "<uuid>",
 *   "eventType":  "STARTED" | "FINISHED",
 *   "occurredAt": "2026-05-02T11:21:13.000Z"   // 선택
 * }
 * ```
 *
 * 검증 선택:
 *  - `adId` — 비어있지 않은 UUID. 텔레메트리는 광고 삭제보다 오래
 *    살아남아야 하므로 `ads` 테이블에 대해 FK 검증을 하지 않는다
 *    ([me.owldev.adsignage.bounded.context.playevent.domain.model.PlayEvent] 문서 참조).
 *  - `eventType` — Jackson이 JSON 문자열을 [PlayEventType]으로 자동 변환 —
 *    [NotNull] 가드는 누락된 필드를 API의 다른 부분과 동일한 필드-오류
 *    맵 형태로 잡아낸다.
 *  - `occurredAt` — **선택**. 플레이어는 정확도(시계 편차 분석) 목적으로
 *    포함하지만, 서버는 [Instant.now]로 폴백하므로 최소 클라이언트가 재생을
 *    보고하기 위해 ISO-8601을 배워야 할 일이 없다.
 */
data class CreatePlayEventRequest(
    @field:NotBlank(message = "adId must not be blank")
    val adId: String?,

    @field:NotNull(message = "eventType must not be null")
    val eventType: PlayEventType?,

    /**
     * 클라이언트에서 이벤트가 발생한 ISO-8601 인스턴트. null 가능 —
     * 컨트롤러가 그 경우 `Instant.now()`로 도장 찍는다.
     */
    @field:JsonFormat(shape = JsonFormat.Shape.STRING)
    val occurredAt: Instant? = null,
)

/**
 * `POST /api/devices/{deviceId}/play-events` 의 응답 본문.
 *
 * 영속화된 이벤트를 메아리치므로 플레이어가 조정할 수 있고(예: 편차 감지를
 * 위해 서버 도장 `receivedAt` 로깅), 어드민이 와이어를 손으로 탐사할 때
 * 후속 GET 없이 생성된 행을 볼 수 있다.
 *
 * AC 20203 Sub-AC 3가 [serverDailyCount]로 응답을 확장했다 —
 * `adsignage.daily-count.zone-id`로 설정된 운영자 로컬 존에서 [occurredAt]을
 * 포함하는 캘린더 일자에 광고에 대한 FINISHED 카운트. 인라인으로 반환하면
 * 플레이어가 localStorage 집계와 비교할 수 있는 권위 있는 디바이스 간
 * 카운트를 얻고, 대시보드는 최근 기록된 이벤트 후의 후속 read를 절약한다.
 * 이전의 "의도적으로 생략" 노트는 대체되었다 — read는 write 트랜잭션 안에서
 * 일어나므로 (`ad_id`, `event_type`, `occurred_at`) 커버링 인덱스에 대한
 * 추가 COUNT(*) 1회면 충분하며 핫패스 예산 안에 충분히 들어간다.
 */
data class PlayEventResponse(
    val id: String,
    val deviceId: String,
    val adId: String,
    val eventType: PlayEventType,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val occurredAt: Instant,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val receivedAt: Instant,
    /**
     * 운영자 로컬 존 기준 [occurredAt]을 포함하는 캘린더 일자에 대해 서버가
     * [adId]에 기록한 FINISHED 이벤트 수. 항상 비음수 `Long`; STARTED
     * 이벤트의 경우 *해당 STARTED의 occurredAt 시점 카운트*이므로 연속된
     * 두 STARTED는 같은 값을 보고, STARTED 뒤의 FINISHED는 N과 N+1을
     * 본다.
     *
     * 응답에 항상 존재 — 절대 생략되지 않음. 플레이어는 이 값을 디바이스
     * 간 조정에 사용하고, 어드민 대시보드는 "오늘 N회 재생(서버)"으로
     * 노출한다.
     */
    val serverDailyCount: Long,
) {
    companion object {
        /**
         * 영속화된 엔터티와 서버의 권위 있는 일일 카운트로 응답을 구성한다.
         * 카운트는 (여기서 계산하는 대신) 호출자가 공급하는데, 카운트 read
         * 가 write와 같은 트랜잭션 안에서 일어나야 하기 때문 —
         * [me.owldev.adsignage.bounded.context.playevent.application.service.PlayEventService.record] 참조.
         */
        fun from(entity: PlayEvent, serverDailyCount: Long): PlayEventResponse = PlayEventResponse(
            id = entity.id,
            deviceId = entity.deviceId,
            adId = entity.adId,
            eventType = entity.eventType,
            occurredAt = entity.occurredAt,
            receivedAt = entity.receivedAt,
            serverDailyCount = serverDailyCount,
        )
    }
}

/**
 * `GET /api/ads/{adId}/play-events/daily-count` 의 응답 본문.
 *
 * 대시보드의 "이 캠페인의 오늘 재생 보여줘" 뷰를 위한 [PlayEventResponse.serverDailyCount]
 * 의 읽기 전용 형제. 결정된 `date`와 `zoneId`를 포함하므로 클라이언트가
 * 설정 드리프트(서버는 2026-05-02 라고 하는데 클라이언트는 2026-05-01을
 * 렌더링)를 운영자가 "47/200"을 보고 플레이어 위젯이 "0/200"이라 표시하는
 * 이유를 의아해하기 전에 감지할 수 있다.
 */
data class DailyPlayCountResponse(
    val adId: String,
    /** [zoneId] 기준 ISO-8601 캘린더 일자. 형식: `YYYY-MM-DD`. */
    val date: String,
    /** [date]가 고정된 IANA 존(예: `Asia/Seoul`). */
    val zoneId: String,
    /** [date] 기준 [adId]의 FINISHED 이벤트 카운트. 비음수. */
    val count: Long,
)
