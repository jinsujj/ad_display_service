package me.owldev.adsignage.bounded.context.playevent.application.port.out.database

import me.owldev.adsignage.bounded.context.playevent.domain.model.PlayEvent
import me.owldev.adsignage.bounded.context.playevent.domain.model.PlayEventType
import java.time.Instant

/**
 * PlayEvent 컨텍스트의 영속 포트. 자기 컨텍스트의 service 가 의존하며,
 * 외부 컨텍스트(ad / device 의 모니터링) 도 "최근 송출 광고" lookup 에서
 * 이 포트만 들이도록 통일한다 — Spring Data 인터페이스를 직접 들이지 않음
 * 으로써 컨텍스트 간 결합 깊이를 한 단계 묶어 둔다.
 */
interface PlayEventRepositoryPort {
    fun save(event: PlayEvent): PlayEvent
    fun findAll(): List<PlayEvent>
    fun countByAdIdAndEventTypeAndOccurredAtBetween(
        adId: String,
        eventType: PlayEventType,
        from: Instant,
        to: Instant,
    ): Long
    fun countDistinctDevicesByAdId(adId: String, eventType: PlayEventType): Long
    fun findLatestPerDeviceByEventTypeSince(
        eventType: PlayEventType,
        threshold: Instant,
    ): List<PlayEvent>

    /**
     * 한 광고 [adId] 에 대해서만, 디바이스당 가장 최근 [eventType] 이벤트
     * (window 안) 를 한 건씩 반환. 라운드 로빈 큐에서 다른 광고가 latest 인
     * 순간에도 "이 광고가 최근에 송출된 디바이스" 를 정확히 식별하는 데 사용.
     */
    fun findLatestPerDeviceByAdIdAndEventTypeSince(
        adId: String,
        eventType: PlayEventType,
        threshold: Instant,
    ): List<PlayEvent>
}
