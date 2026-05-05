package me.owldev.adsignage.bounded.context.queue.adapter.`in`.api

import jakarta.validation.Valid
import me.owldev.adsignage.bounded.context.queue.application.service.DeviceAdQueueService
import me.owldev.adsignage.bounded.context.queue.domain.dto.AddAdToQueueRequest
import me.owldev.adsignage.bounded.context.queue.domain.dto.AddAdToQueueResponse
import me.owldev.adsignage.bounded.context.queue.domain.dto.QueuedAdItem
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

/**
 * 디바이스 ↔ 광고 큐 관리 엔드포인트.
 *
 * 운영자는 이 엔드포인트로 "이 디바이스에 어떤 광고들을 송출할지" 명시적
 * 으로 담는다. 같은 광고를 여러 디바이스에 담을 수 있고, 한 디바이스 큐
 * 에는 여러 광고가 들어갈 수 있다.
 *
 * 큐가 변경되면 PLAYLIST_UPDATE SSE 이벤트가 발행되어 해당 디바이스가
 * 즉시 새 플레이리스트를 가져온다.
 *
 * 인증: 어드민 전용 — JWT(ROLE_ADVERTISER) 필요. SecurityConfig 의
 * `.anyRequest().authenticated()` 기본 규칙으로 보호됨.
 */
@RestController
class DeviceAdQueueController(
    private val deviceAdQueueService: DeviceAdQueueService,
) {

    /**
     * 디바이스 큐에 담긴 광고 목록. 큐에 담긴 순서(addedAt 내림차순)로
     * 광고의 메타와 현재 상태(SCHEDULED/ACTIVE/EXPIRED)도 같이 내려준다.
     */
    @GetMapping(
        "/api/devices/{deviceId}/ads",
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    fun listQueue(@PathVariable deviceId: String): ResponseEntity<List<QueuedAdItem>> {
        val items = deviceAdQueueService.listForDevice(deviceId)
            ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(items)
    }

    /**
     * 광고를 디바이스 큐에 추가. 멱등 — 이미 큐에 있으면 200 + 기존 행을
     * 그대로 반환. 신규 추가 시 PLAYLIST_UPDATE 이벤트 발행으로 디바이스
     * 즉시 새 플레이리스트 fetch.
     */
    @PostMapping(
        "/api/devices/{deviceId}/ads",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    fun addToQueue(
        @PathVariable deviceId: String,
        @Valid @RequestBody body: AddAdToQueueRequest,
    ): ResponseEntity<AddAdToQueueResponse> {
        val response = deviceAdQueueService.addToQueue(deviceId, body.adId!!)
            ?: return ResponseEntity.notFound().build()
        val status = if (response.created) HttpStatus.CREATED else HttpStatus.OK
        return ResponseEntity.status(status).body(response)
    }

    /**
     * 디바이스 큐에서 광고 제거. 없는 행을 지워도 204 (멱등).
     * 큐에서 빠진 즉시 PLAYLIST_UPDATE 발행 → 디바이스가 송출에서 제외.
     */
    @DeleteMapping("/api/devices/{deviceId}/ads/{adId}")
    fun removeFromQueue(
        @PathVariable deviceId: String,
        @PathVariable adId: String,
    ): ResponseEntity<Void> {
        deviceAdQueueService.removeFromQueue(deviceId, adId)
        return ResponseEntity.noContent().build()
    }
}
