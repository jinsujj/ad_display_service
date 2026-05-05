package me.owldev.adsignage.bounded.context.playevent.adapter.out.database

import me.owldev.adsignage.bounded.context.playevent.application.port.out.database.PlayEventRepositoryPort
import me.owldev.adsignage.bounded.context.playevent.domain.model.PlayEvent
import me.owldev.adsignage.bounded.context.playevent.domain.model.PlayEventType
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
class PlayEventRepositoryAdapter(
    private val playEventRepository: PlayEventRepository,
) : PlayEventRepositoryPort {
    override fun save(event: PlayEvent): PlayEvent = playEventRepository.save(event)
    override fun findAll(): List<PlayEvent> = playEventRepository.findAll()
    override fun countByAdIdAndEventTypeAndOccurredAtBetween(
        adId: String,
        eventType: PlayEventType,
        from: Instant,
        to: Instant,
    ): Long = playEventRepository.countByAdIdAndEventTypeAndOccurredAtBetween(adId, eventType, from, to)
    override fun countDistinctDevicesByAdId(adId: String, eventType: PlayEventType): Long =
        playEventRepository.countDistinctDevicesByAdId(adId, eventType)
    override fun findLatestPerDeviceByEventTypeSince(
        eventType: PlayEventType,
        threshold: Instant,
    ): List<PlayEvent> = playEventRepository.findLatestPerDeviceByEventTypeSince(eventType, threshold)
}
