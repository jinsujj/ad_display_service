package me.owldev.adsignage.domain.playevent

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.Instant

/**
 * [PlayEvent]를 위한 Spring Data JPA repository.
 *
 * 상속된 CRUD 표면 외에 두 읽기 경로가 지원됨:
 *
 *  - [countByAdIdAndEventTypeAndOccurredAtBetween] — 일일 cap 집계
 *    (`COUNT(*) WHERE ad_id = ? AND event_type = 'FINISHED' AND
 *    occurred_at IN [start, end)`). 향후 관리자 보고와 관리자 대시보드에
 *    "서버 말로는: 오늘 N/M회 재생"을 표면화할 수 있는 형제 sub-AC가
 *    사용함. 복합 인덱스 `idx_play_events_ad_event_time`이 이 쿼리를 커버.
 *  - [countDistinctDevicesByAdId] — "이 광고를 재생한 고유 디바이스가
 *    몇 개인가?" 다음 AC의 크로스 디바이스 cap 조정을 뒷받침하는 동일
 *    텔레메트리.
 *
 * Spring Data가 메서드 이름에서 두 쿼리를 모두 파생함; distinct-device
 * 카운트에 대한 명시적 JPQL은 의도를 명확하게 하기 위함일 뿐.
 */
@Repository
interface PlayEventRepository : JpaRepository<PlayEvent, String> {

    /**
     * `occurred_at`이 반-개방 구간 `[from, to)`에 속하는 [eventType]의
     * [adId]에 대한 재생 이벤트 수. 하한 포함, 상한 제외 —
     * `web/lib/playlist.ts: isAdActive`가 이미 day-window를 모델링하는 방식과
     * 일치.
     */
    fun countByAdIdAndEventTypeAndOccurredAtBetween(
        adId: String,
        eventType: PlayEventType,
        from: Instant,
        to: Instant,
    ): Long

    /**
     * 광고에 대한 distinct-device 팬아웃. 캠페인 보고("이 광고가 오늘 12개
     * 디바이스에 도달했는가?")에 유용; 핫 쓰기 경로에는 포함되지 않음.
     */
    @Query(
        "SELECT COUNT(DISTINCT pe.deviceId) FROM PlayEvent pe " +
            "WHERE pe.adId = :adId AND pe.eventType = :eventType",
    )
    fun countDistinctDevicesByAdId(
        @Param("adId") adId: String,
        @Param("eventType") eventType: PlayEventType,
    ): Long
}
