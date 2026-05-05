package me.owldev.adsignage.bounded.context.playlist.adapter.out.sse

import me.owldev.adsignage.bounded.context.device.adapter.`in`.sse.SseEmitterRegistry
import me.owldev.adsignage.common.sse.PlaylistUpdatedPayload
import me.owldev.adsignage.common.sse.SseEventNames
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

/**
 * Sub-AC 50003.3 — "playlist-updated" SSE 이벤트의 와이어 측 publisher.
 *
 * device 컨텍스트의 [SseEmitterRegistry] 에서 `deviceId` 에 대한 모든 라이브
 * emitter 를 가져와 단일 SSE 이벤트를 브로드캐스트하므로, 해당 디바이스에
 * 연결된 모든 플레이어 페이지가 새 플레이리스트를 재조회하게 된다.
 *
 * 도메인 코드(AdService, DeviceAdQueueService) 가 SSE 배관에서 자유로워지도록
 * 타입화된 메서드 하나만 노출 + `name(...) + .data(...)` 빌더 모양을 중앙화.
 */
@Service
class PlaylistEventPublisher(
    private val registry: SseEmitterRegistry,
) {

    private val log = LoggerFactory.getLogger(PlaylistEventPublisher::class.java)

    fun publishPlaylistUpdated(deviceId: String, payload: PlaylistUpdatedPayload): Int {
        require(deviceId.isNotBlank()) { "deviceId must not be blank" }
        return broadcast(deviceId, payload)
    }

    fun publishPlaylistUpdated(deviceId: String, payload: Any): Int {
        require(deviceId.isNotBlank()) { "deviceId must not be blank" }
        return broadcast(deviceId, payload)
    }

    private fun broadcast(deviceId: String, payload: Any): Int {
        // 청자가 없는 디바이스에 대해 빌드를 단축하기 위해 라이브 emitter 를
        // 먼저 스냅샷.
        val live = registry.getByDeviceId(deviceId)
        if (live.isEmpty()) {
            log.debug("PLAYLIST_UPDATE skipped (no live emitters): device={}", deviceId)
            return 0
        }

        val event = SseEmitter.event()
            .name(SseEventNames.PLAYLIST_UPDATE)
            .data(payload)

        val delivered = registry.broadcast(deviceId, event)
        log.info(
            "PLAYLIST_UPDATE → device={} emitters={} delivered={}",
            deviceId,
            live.size,
            delivered,
        )
        return delivered
    }
}
