package me.owldev.adsignage.bounded.context.queue.application.service

import me.owldev.adsignage.bounded.context.device.application.port.out.database.DeviceRepositoryPort
import me.owldev.adsignage.bounded.context.queue.application.port.out.database.DeviceAdQueueRepositoryPort
import me.owldev.adsignage.bounded.context.queue.domain.dto.AddAdToQueueResponse
import me.owldev.adsignage.bounded.context.queue.domain.dto.QueuedAdItem
import me.owldev.adsignage.bounded.context.queue.domain.model.DeviceAdQueue
import me.owldev.adsignage.bounded.context.queue.domain.model.DeviceAdQueueId
import me.owldev.adsignage.bounded.context.ad.application.port.out.database.AdRepositoryPort
import me.owldev.adsignage.bounded.context.playlist.adapter.out.sse.PlaylistUpdatedEvent
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 디바이스 ↔ 광고 큐 use case 모음.
 *
 *  - listForDevice: 큐를 운영자가 담은 순서로 보여줌
 *  - addToQueue:    멱등 추가 + PLAYLIST_UPDATE 발행
 *  - removeFromQueue: 멱등 제거 + PLAYLIST_UPDATE 발행
 *
 * 디바이스/광고 존재 여부 검증은 service 레벨에서 결정 — 컨트롤러는 결과를
 * Optional/null/예외 형태로 받아 HTTP 코드만 매핑한다.
 *
 * 외부 컨텍스트 의존:
 *  - device: 이미 헥사고날 — DeviceRepositoryPort
 *  - ad:     아직 레거시 — AdRepository (Spring Data); ad 마이그레이션 시 port 로 교체
 *  - sse:    cross-cutting 인프라 (이벤트 발행)
 */
@Service
class DeviceAdQueueService(
    private val queueRepositoryPort: DeviceAdQueueRepositoryPort,
    private val deviceRepositoryPort: DeviceRepositoryPort,
    private val adRepositoryPort: AdRepositoryPort,
    private val eventPublisher: ApplicationEventPublisher,
) {
    private val log = LoggerFactory.getLogger(DeviceAdQueueService::class.java)

    /**
     * 디바이스가 존재하지 않으면 null 반환 → 컨트롤러가 404 매핑.
     * 큐가 비었으면 빈 리스트.
     */
    @Transactional(readOnly = true)
    fun listForDevice(deviceId: String): List<QueuedAdItem>? {
        if (!deviceRepositoryPort.existsById(deviceId)) return null
        val rows = queueRepositoryPort.findAllByIdDeviceIdOrderByAddedAtDesc(deviceId)
        if (rows.isEmpty()) return emptyList()

        val adIds = rows.map { it.id.adId }
        val adsById = adRepositoryPort.findAllById(adIds).associateBy { it.id }
        return rows.mapNotNull { q ->
            val ad = adsById[q.id.adId] ?: return@mapNotNull null
            QueuedAdItem(
                adId = ad.id,
                title = ad.title,
                videoFilename = ad.videoFilename,
                startTime = ad.startTime,
                endTime = ad.endTime,
                dailyPlayCount = ad.dailyPlayCount,
                campaignStartDate = ad.campaignStartDate,
                campaignEndDate = ad.campaignEndDate,
                status = ad.computeStatus().name,
                addedAt = q.addedAt,
            )
        }
    }

    /**
     * 광고를 디바이스 큐에 추가. 멱등 — 이미 큐에 있으면 created=false 로
     * 기존 행 그대로 반환. 신규 추가 시 PLAYLIST_UPDATE SSE 이벤트 발행.
     *
     * 디바이스/광고가 존재하지 않으면 null → 컨트롤러가 404 매핑.
     */
    @Transactional
    fun addToQueue(deviceId: String, adId: String): AddAdToQueueResponse? {
        val trimmedAdId = adId.trim()

        if (!deviceRepositoryPort.existsById(deviceId)) return null
        val ad = adRepositoryPort.findById(trimmedAdId) ?: return null

        val pk = DeviceAdQueueId(deviceId = deviceId, adId = trimmedAdId)
        val existing = queueRepositoryPort.findById(pk)
        val saved = existing ?: queueRepositoryPort.save(DeviceAdQueue(id = pk))

        if (existing == null) {
            log.info("queue.add deviceId={} adId={}", deviceId, trimmedAdId)
            // 큐에 새로 들어왔으니 해당 디바이스가 즉시 플레이리스트 재조회
            // 하도록 PLAYLIST_UPDATE 발행. AFTER_COMMIT 으로 묶여 있어 이
            // 트랜잭션이 커밋된 후에만 SSE 가 나간다.
            try {
                eventPublisher.publishEvent(
                    PlaylistUpdatedEvent(
                        advertiserId = ad.advertiserId,
                        adId = ad.id,
                    ),
                )
            } catch (ex: Exception) {
                log.warn("queue.add publish failed deviceId={} adId={}: {}", deviceId, trimmedAdId, ex.message)
            }
        } else {
            log.info("queue.add (no-op, already queued) deviceId={} adId={}", deviceId, trimmedAdId)
        }

        return AddAdToQueueResponse(
            deviceId = deviceId,
            adId = trimmedAdId,
            addedAt = saved.addedAt,
            created = existing == null,
        )
    }

    /**
     * 디바이스 큐에서 광고 제거. 없는 행을 지워도 0 반환 (멱등).
     * 큐에서 빠진 즉시 PLAYLIST_UPDATE 발행 → 디바이스가 송출에서 제외.
     */
    @Transactional
    fun removeFromQueue(deviceId: String, adId: String): Int {
        val removed = queueRepositoryPort.deleteOne(deviceId, adId)
        log.info("queue.remove deviceId={} adId={} removed={}", deviceId, adId, removed)
        if (removed > 0) {
            // 광고 메타가 필요하지만 이미 삭제됐을 수도 있어 best-effort.
            val advertiserId = adRepositoryPort.findById(adId)?.advertiserId.orEmpty()
            try {
                eventPublisher.publishEvent(
                    PlaylistUpdatedEvent(
                        advertiserId = advertiserId,
                        adId = adId,
                    ),
                )
            } catch (ex: Exception) {
                log.warn("queue.remove publish failed deviceId={} adId={}: {}", deviceId, adId, ex.message)
            }
        }
        return removed
    }
}
