package me.owldev.adsignage.bounded.context.playlist.application.service

import me.owldev.adsignage.bounded.context.ad.application.port.out.database.AdRepositoryPort
import me.owldev.adsignage.bounded.context.ad.domain.model.AdStatus
import me.owldev.adsignage.bounded.context.assignment.application.port.out.database.DeviceAssignmentRepositoryPort
import me.owldev.adsignage.bounded.context.playlist.domain.dto.DevicePlaylistResponse
import me.owldev.adsignage.bounded.context.playlist.domain.dto.PlaylistAdResponse
import me.owldev.adsignage.bounded.context.queue.application.port.out.database.DeviceAdQueueRepositoryPort
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * 디바이스 플레이리스트 생성 서비스. 자체 모델/persistence 가 없는 (자기
 * 컨텍스트의 entity 가 없음) 컨텍스트로, 다른 세 컨텍스트(ad / assignment /
 * queue)의 port 를 조합해 디바이스에 송출할 광고 목록을 만든다.
 *
 * 계산:
 *  1. device → 활성 매핑(restaurant_id) 조회. 위치/식별 용도로만 응답에
 *     포함되며, 광고 선정에는 매핑 존재 여부가 영향을 주지 않는다.
 *  2. **device_ad_queue 가 진실의 원천**. 운영자가 이 디바이스 큐에 담아둔
 *     광고들 중에서 캠페인 기간이 ACTIVE 인 것만 송출 대상으로 반환한다.
 *  3. 일일 시간 윈도우와 dailyPlayCount 는 응답에 그대로 실어 보내고 시간
 *     필터는 플레이어가 책임진다.
 */
@Service
class PlaylistService(
    private val adRepositoryPort: AdRepositoryPort,
    private val assignmentRepositoryPort: DeviceAssignmentRepositoryPort,
    private val queueRepositoryPort: DeviceAdQueueRepositoryPort,
) {
    private val log = LoggerFactory.getLogger(PlaylistService::class.java)

    @Transactional(readOnly = true)
    fun buildPlaylist(deviceId: String): DevicePlaylistResponse {
        val now = Instant.now()
        val assignment = assignmentRepositoryPort.findByDeviceIdAndActiveTrue(deviceId)
        val restaurantId = assignment?.restaurantId

        val queueRows = queueRepositoryPort.findAllByIdDeviceIdOrderByAddedAtDesc(deviceId)
        val ads: List<PlaylistAdResponse> = if (queueRows.isEmpty()) {
            emptyList()
        } else {
            val queuedIds = queueRows.map { it.id.adId }
            adRepositoryPort.findAllById(queuedIds)
                .filter { it.computeStatus() == AdStatus.ACTIVE }
                .map { ad ->
                    PlaylistAdResponse(
                        adId = ad.id,
                        title = ad.title,
                        videoUrl = "/api/videos/${ad.videoFilename}",
                        scheduleId = ad.id, // 1:1 임베드 — schedule_id == ad_id
                        startTime = ad.startTime,
                        endTime = ad.endTime,
                        dailyCount = ad.dailyPlayCount,
                    )
                }
        }

        log.info(
            "GET /api/devices/{}/playlist restaurantId={} queued={} active_ads={}",
            deviceId, restaurantId, queueRows.size, ads.size,
        )
        return DevicePlaylistResponse(
            deviceId = deviceId,
            restaurantId = restaurantId,
            ads = ads,
            fetchedAt = now,
        )
    }
}
