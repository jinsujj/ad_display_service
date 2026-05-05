package me.owldev.adsignage.bounded.context.playlist.adapter.out.sse

import me.owldev.adsignage.bounded.context.assignment.application.port.out.database.DeviceAssignmentRepositoryPort
import me.owldev.adsignage.common.sse.PlaylistUpdatedPayload
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

/**
 * [PlaylistUpdatedEvent] 를 SSE 와이어로 브리지하여, 변경의 영향을 받을 수
 * 있는 디바이스에 현재 연결된 모든 emitter 로 PLAYLIST_UPDATE 이벤트를 브로드캐스트.
 *
 *  1. AdService/DeviceAdQueueService 가 변경을 쓰고 [PlaylistUpdatedEvent] 발행
 *  2. 트랜잭션 커밋 후에만 이 리스너가 실행 (AFTER_COMMIT)
 *  3. assignment 의 활성 매핑을 통해 영향받는 device_id 집합 해석
 *  4. [PlaylistEventPublisher] 로 각 디바이스에 PLAYLIST_UPDATE 푸시
 *  5. Next.js 플레이어가 SSE 로 받고 플레이리스트 재조회
 */
@Component
class PlaylistUpdatedSseListener(
    private val publisher: PlaylistEventPublisher,
    private val assignmentRepository: DeviceAssignmentRepositoryPort,
) {

    private val log = LoggerFactory.getLogger(PlaylistUpdatedSseListener::class.java)

    @TransactionalEventListener(
        phase = TransactionPhase.AFTER_COMMIT,
        fallbackExecution = true,
    )
    fun onPlaylistUpdated(event: PlaylistUpdatedEvent) {
        val activeAssignments = try {
            assignmentRepository.findAllByActiveTrue()
        } catch (ex: Exception) {
            log.warn(
                "PLAYLIST_UPDATE listener: failed to load active assignments for adId={}: {}",
                event.adId, ex.message,
            )
            return
        }

        if (activeAssignments.isEmpty()) {
            log.debug(
                "PLAYLIST_UPDATE listener: no active device assignments — skipping broadcast (adId={})",
                event.adId,
            )
            return
        }

        var totalDelivered = 0
        var devicesNotified = 0
        for (assignment in activeAssignments) {
            val payload = PlaylistUpdatedPayload(
                deviceId = assignment.deviceId,
                restaurantId = assignment.restaurantId,
                updatedAt = event.changedAt,
            )
            try {
                val delivered = publisher.publishPlaylistUpdated(assignment.deviceId, payload)
                if (delivered > 0) {
                    devicesNotified++
                    totalDelivered += delivered
                }
            } catch (ex: Exception) {
                log.warn(
                    "PLAYLIST_UPDATE fan-out failed for device={}: {}",
                    assignment.deviceId, ex.message,
                )
            }
        }
        log.info(
            "PLAYLIST_UPDATE fan-out adId={} advertiserId={} activeDevices={} devicesNotified={} totalEmittersDelivered={}",
            event.adId,
            event.advertiserId,
            activeAssignments.size,
            devicesNotified,
            totalDelivered,
        )
    }
}
