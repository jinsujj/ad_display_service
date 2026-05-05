package me.owldev.adsignage.domain.queue

import com.fasterxml.jackson.annotation.JsonFormat
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import me.owldev.adsignage.domain.ad.AdRepository
import me.owldev.adsignage.domain.ad.AdStatus
import me.owldev.adsignage.bounded.context.device.application.port.out.database.DeviceRepositoryPort
import me.owldev.adsignage.sse.PlaylistUpdatedEvent
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

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
    private val queueRepository: DeviceAdQueueRepository,
    private val deviceRepository: DeviceRepositoryPort,
    private val adRepository: AdRepository,
    private val eventPublisher: ApplicationEventPublisher,
) {
    private val log = LoggerFactory.getLogger(DeviceAdQueueController::class.java)

    /**
     * 디바이스 큐에 담긴 광고 목록. 큐에 담긴 순서(addedAt 내림차순)로
     * 광고의 메타와 현재 상태(SCHEDULED/ACTIVE/EXPIRED)도 같이 내려준다.
     */
    @GetMapping(
        "/api/devices/{deviceId}/ads",
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    @Transactional(readOnly = true)
    fun listQueue(@PathVariable deviceId: String): ResponseEntity<List<QueuedAdItem>> {
        if (!deviceRepository.existsById(deviceId)) {
            return ResponseEntity.notFound().build()
        }
        val rows = queueRepository.findAllByIdDeviceIdOrderByAddedAtDesc(deviceId)
        if (rows.isEmpty()) return ResponseEntity.ok(emptyList())

        val adIds = rows.map { it.id.adId }
        val adsById = adRepository.findAllById(adIds).associateBy { it.id }

        val items = rows.mapNotNull { q ->
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
    @Transactional
    fun addToQueue(
        @PathVariable deviceId: String,
        @Valid @RequestBody body: AddAdToQueueRequest,
    ): ResponseEntity<AddAdToQueueResponse> {
        val adId = body.adId!!.trim()

        if (!deviceRepository.existsById(deviceId)) {
            return ResponseEntity.notFound().build()
        }
        val ad = adRepository.findById(adId).orElse(null)
            ?: return ResponseEntity.notFound().build()

        val pk = DeviceAdQueueId(deviceId = deviceId, adId = adId)
        val existing = queueRepository.findById(pk).orElse(null)
        val saved = existing ?: queueRepository.save(DeviceAdQueue(id = pk))

        if (existing == null) {
            log.info("queue.add deviceId={} adId={}", deviceId, adId)
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
                log.warn("queue.add publish failed deviceId={} adId={}: {}", deviceId, adId, ex.message)
            }
        } else {
            log.info("queue.add (no-op, already queued) deviceId={} adId={}", deviceId, adId)
        }

        val status = if (existing == null) HttpStatus.CREATED else HttpStatus.OK
        return ResponseEntity.status(status).body(
            AddAdToQueueResponse(
                deviceId = deviceId,
                adId = adId,
                addedAt = saved.addedAt,
                created = existing == null,
            ),
        )
    }

    /**
     * 디바이스 큐에서 광고 제거. 없는 행을 지워도 204 (멱등).
     * 큐에서 빠진 즉시 PLAYLIST_UPDATE 발행 → 디바이스가 송출에서 제외.
     */
    @DeleteMapping("/api/devices/{deviceId}/ads/{adId}")
    @Transactional
    fun removeFromQueue(
        @PathVariable deviceId: String,
        @PathVariable adId: String,
    ): ResponseEntity<Void> {
        val removed = queueRepository.deleteOne(deviceId, adId)
        log.info("queue.remove deviceId={} adId={} removed={}", deviceId, adId, removed)
        if (removed > 0) {
            // 광고 메타가 필요하지만 이미 삭제됐을 수도 있어 best-effort.
            val advertiserId = adRepository.findById(adId).orElse(null)?.advertiserId.orEmpty()
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
        return ResponseEntity.noContent().build()
    }
}

/* -------- DTOs -------- */

data class AddAdToQueueRequest(
    @field:NotBlank(message = "adId must not be blank")
    @field:Size(max = 36, message = "adId must be at most 36 characters")
    val adId: String?,
)

data class AddAdToQueueResponse(
    val deviceId: String,
    val adId: String,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val addedAt: Instant,
    val created: Boolean,
)

data class QueuedAdItem(
    val adId: String,
    val title: String,
    val videoFilename: String,
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "HH:mm")
    val startTime: LocalTime,
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "HH:mm")
    val endTime: LocalTime,
    val dailyPlayCount: Int,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val campaignStartDate: LocalDate,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val campaignEndDate: LocalDate,
    val status: String,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val addedAt: Instant,
)
