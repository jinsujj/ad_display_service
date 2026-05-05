package me.owldev.adsignage.bounded.context.device.application.service

import me.owldev.adsignage.bounded.context.device.application.port.out.database.DeviceRepositoryPort
import me.owldev.adsignage.bounded.context.device.domain.dto.AssignmentHistoryItem
import me.owldev.adsignage.bounded.context.device.domain.dto.CurrentAdDto
import me.owldev.adsignage.bounded.context.device.domain.dto.CurrentRestaurantDto
import me.owldev.adsignage.bounded.context.device.domain.dto.DeviceDetailResponse
import me.owldev.adsignage.bounded.context.device.domain.dto.DeviceListItem
import me.owldev.adsignage.bounded.context.device.domain.dto.QueuedAdSummary
import me.owldev.adsignage.bounded.context.device.domain.dto.RegisterDeviceResponse
import me.owldev.adsignage.bounded.context.device.domain.model.Device
import me.owldev.adsignage.bounded.context.restaurant.application.port.out.database.RestaurantRepositoryPort
import me.owldev.adsignage.bounded.context.ad.application.port.out.database.AdRepositoryPort
import me.owldev.adsignage.bounded.context.ad.domain.model.AdStatus
import me.owldev.adsignage.domain.assignment.DeviceAssignmentRepository
import me.owldev.adsignage.bounded.context.playevent.application.port.out.database.PlayEventRepositoryPort
import me.owldev.adsignage.bounded.context.playevent.domain.model.PlayEventType
import me.owldev.adsignage.bounded.context.queue.application.port.out.database.DeviceAdQueueRepositoryPort
import me.owldev.adsignage.sse.SseEmitterRegistry
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * 디바이스 라이프사이클 — 등록 / heartbeat / 오프라인 신고 / 삭제 — 와
 * 어드민 모니터 화면에 필요한 디바이스 목록·상세 조회를 책임진다.
 *
 * 외부 컨텍스트(restaurant 외) 의존:
 *  - assignment / queue / ad / playevent: 아직 헥사고날로 옮기기 전이라
 *    레거시 Spring Data 인터페이스를 그대로 의존한다 — 그 컨텍스트들이
 *    각자 마이그레이션될 때 이 import 들도 port 로 갈아끼운다.
 *  - sse: 컨텍스트가 아닌 cross-cutting 인프라. 디바이스 오프라인 신고가
 *    SSE 연결을 강제 종료해야 하므로 직접 의존.
 */
@Service
class DeviceService(
    private val deviceRepositoryPort: DeviceRepositoryPort,
    private val assignmentRepository: DeviceAssignmentRepository,
    private val restaurantRepositoryPort: RestaurantRepositoryPort,
    private val queueRepository: DeviceAdQueueRepositoryPort,
    private val adRepositoryPort: AdRepositoryPort,
    private val playEventRepository: PlayEventRepositoryPort,
    private val sseRegistry: SseEmitterRegistry,
) {
    private val log = LoggerFactory.getLogger(DeviceService::class.java)

    /**
     * 멱등 등록 — 이미 있는 device_id 면 도메인 메서드로 활동 시각만 갱신.
     * device_name 은 처음 생성된 값을 유지(클라이언트가 매번 갱신하면 운영자
     * 라벨이 덮어 쓰일 수 있으므로). 명시적 patch 가 필요하면 별도 엔드포인트.
     */
    @Transactional
    fun register(deviceId: String, deviceName: String?): RegisterDeviceResponse {
        val trimmedId = deviceId.trim()
        val name = deviceName?.trim()?.takeIf { it.isNotEmpty() }
            ?: "Device-${trimmedId.take(8)}"

        val saved = deviceRepositoryPort.findById(trimmedId)?.also {
            it.touch()
        } ?: deviceRepositoryPort.save(
            Device(
                deviceId = trimmedId,
                deviceName = name,
                lastSeenAt = Instant.now(),
            ),
        )

        log.info("device registered/seen deviceId={} name={}", saved.deviceId, saved.deviceName)
        return RegisterDeviceResponse(
            deviceId = saved.deviceId,
            deviceName = saved.deviceName,
            registeredAt = saved.registeredAt,
            lastSeenAt = saved.lastSeenAt,
        )
    }

    /**
     * PlayerClient 가 5초마다 호출. lastSeenAt 만 갱신하므로 가벼움(write 1회).
     * Android 앱이 sendBeacon 도 못 보내고 SSE TCP 도 nginx idle 안에 갇혀
     * 죽는 케이스에서, 마지막 heartbeat 가 윈도우 밖으로 나가면 즉시 오프라인
     * 판정.
     */
    @Transactional
    fun heartbeat(deviceId: String) {
        deviceRepositoryPort.findById(deviceId)?.also {
            it.touch()
            deviceRepositoryPort.save(it)
        }
    }

    /**
     * 디바이스가 종료/슬립으로 들어가면서 자기 자신을 즉시 오프라인으로
     * 알리는 신호. SSE 연결을 강제로 끊고 lastSeenAt 을 liveness 윈도우 밖으로
     * 끌어내려 어드민 모니터의 다음 폴링에서 곧바로 오프라인 카드로 표시되게
     * 한다.
     */
    @Transactional
    fun reportOffline(deviceId: String) {
        sseRegistry.forceCloseAll(deviceId)
        deviceRepositoryPort.findById(deviceId)?.also {
            it.forceOffline(LIVENESS_WINDOW_SECONDS)
            deviceRepositoryPort.save(it)
        }
        log.info("device offline reported deviceId={}", deviceId)
    }

    /**
     * 디바이스 + 활성/비활성 매핑 이력 + 큐를 일괄 삭제. 디바이스 앱이
     * 다시 켜지면 멱등 register 가 새 행을 만들어 다시 등록되므로 재시작이나
     * 분실/교체 디바이스 정리에 안전하다.
     */
    @Transactional
    fun delete(deviceId: String) {
        // 큐 + 매핑 이력 먼저 (외래 데이터 모두 정리)
        val removedQueue = queueRepository.deleteAllByDeviceId(deviceId)
        val removedAssignments = assignmentRepository.deleteAllByDeviceId(deviceId)
        // 그 다음 디바이스 자체. 없는 id 면 deleteById가 EmptyResultDataAccessException
        // 을 던지므로 existsById 로 가드.
        val existed = deviceRepositoryPort.existsById(deviceId)
        if (existed) deviceRepositoryPort.deleteById(deviceId)
        log.info(
            "DELETE /api/devices/{} removed_device={} removed_queue={} removed_assignments={}",
            deviceId, existed, removedQueue, removedAssignments,
        )
    }

    /**
     * 단일 디바이스 상세 + 매핑 이력 (활성/비활성 모두, 최신순).
     * /devices/{deviceId} 페이지가 사용. 디바이스가 없으면 null 반환 — 컨트롤러가
     * 404 로 매핑.
     */
    @Transactional(readOnly = true)
    fun getDetail(deviceId: String): DeviceDetailResponse? {
        val device = deviceRepositoryPort.findById(deviceId) ?: return null

        val assignmentsRaw = assignmentRepository.findAllByDeviceIdOrderByAssignedAtDesc(deviceId)
        val restaurantsById = restaurantRepositoryPort.findAll().associateBy { it.restaurantId }

        val history = assignmentsRaw.map { a ->
            val r = restaurantsById[a.restaurantId]
            AssignmentHistoryItem(
                assignmentId = a.id,
                restaurantId = a.restaurantId,
                restaurantName = r?.name ?: "(삭제된 음식점)",
                address = r?.address,
                assignedAt = a.assignedAt,
                active = a.active,
            )
        }
        val current = history.firstOrNull { it.active }

        return DeviceDetailResponse(
            deviceId = device.deviceId,
            deviceName = device.deviceName,
            registeredAt = device.registeredAt,
            lastSeenAt = device.lastSeenAt,
            currentAssignment = current,
            history = history,
        )
    }

    /**
     * 어드민 디바이스 탭의 목록 — 활성 매핑/큐/현재 송출 광고/온라인 여부를
     * batch 로 합쳐서 반환. N+1 회피를 위해 모든 보조 조회는 한 번씩만.
     */
    @Transactional(readOnly = true)
    fun list(): List<DeviceListItem> {
        val devices = deviceRepositoryPort.findAllByOrderByRegisteredAtDesc()
        if (devices.isEmpty()) return emptyList()

        val now = Instant.now()
        // online 판정의 fallback. SSE 연결이 없어도 최근 90초 안에 play-event
        // 가 들어왔으면 살아있다고 본다. 한 광고 길이(보통 15-30초)의 ~3배.
        val livenessThreshold = now.minusSeconds(LIVENESS_WINDOW_SECONDS)
        // currentAd lookup 은 더 긴 윈도우. <video loop> 가 단일 광고를 무한
        // 재생할 때 HTML5 의 `play` 이벤트가 매 loop 마다 다시 발사되지 않아
        // STARTED 가 자주 안 들어온다. online 은 SSE 가 따로 보장하므로
        // currentAd 만 길게 잡아도 ghost LIVE 위험 낮음(디바이스 죽으면 SSE 가
        // 끊겨 online=false → currentAd 도 null 로 폴백).
        val currentAdThreshold = now.minusSeconds(CURRENT_AD_WINDOW_SECONDS)

        val activeAssignments = assignmentRepository.findAllByActiveTrue()
            .associateBy { it.deviceId }
        val restaurantsById = restaurantRepositoryPort.findAll().associateBy { it.restaurantId }

        // 디바이스 큐를 한 번에 모아 device_id 별로 묶어두면 N+1 을 피할 수 있다.
        // 어드민 디바이스 탭에서 모니터링 썸네일을 그리는 데 쓰이며, 결과는
        // addedAt 내림차순으로 정렬되어 운영자가 큐에 쌓은 순서를 그대로 본다.
        val queueByDevice = queueRepository.findAll()
            .sortedByDescending { it.addedAt }
            .groupBy { it.id.deviceId }
        val queuedAdIds = queueByDevice.values.flatten().map { it.id.adId }.toSet()

        // "지금 재생 중인 광고" 를 디바이스마다 한 건씩 batch 조회. STARTED 만
        // 보고 동일 디바이스의 더 최신 STARTED 가 없는 행만 떨어진다.
        val latestStartedByDevice = playEventRepository
            .findLatestPerDeviceByEventTypeSince(PlayEventType.STARTED, currentAdThreshold)
            .associateBy { it.deviceId }
        val currentlyPlayingAdIds = latestStartedByDevice.values.map { it.adId }.toSet()

        // 큐 광고 + 현재 재생 광고를 합쳐 한 번에 fetch.
        val allAdIds = queuedAdIds + currentlyPlayingAdIds
        val adsById = if (allAdIds.isEmpty()) {
            emptyMap()
        } else {
            adRepositoryPort.findAllById(allAdIds).associateBy { it.id }
        }

        return devices.map { d ->
            val a = activeAssignments[d.deviceId]
            val r = a?.let { restaurantsById[it.restaurantId] }
            val queuedAds = (queueByDevice[d.deviceId] ?: emptyList()).mapNotNull { q ->
                val ad = adsById[q.id.adId] ?: return@mapNotNull null
                QueuedAdSummary(
                    adId = ad.id,
                    title = ad.title,
                    videoFilename = ad.videoFilename,
                    status = ad.computeStatus().name,
                )
            }

            // online 판정: SSE 연결이 살아있거나(가장 정확) 최근 play-event 가
            // 있거나(SSE 연결이 일시적으로 끊겼더라도 살아있다는 신호).
            val sseConnected = sseRegistry.isDeviceConnected(d.deviceId)
            val recentlyActive = d.lastSeenAt?.isAfter(livenessThreshold) == true
            val online = sseConnected || recentlyActive

            // currentAd 는 다음 *모든* 조건을 만족할 때만 채운다 — 그렇지
            // 않으면 stale STARTED 이벤트가 디바이스 실제 송출과 어긋나는
            // 버그(예: 큐를 비웠는데 직전 STARTED 가 윈도우 안이라 LIVE 로
            // 잘못 표시).
            //   1) online                  ← SSE 연결 또는 최근 활동
            //   2) 그 광고가 *지금도 큐에 있음* ← 운영자가 빼면 즉시 무효
            //   3) 그 광고가 캠페인 ACTIVE  ← 만료/시작전 광고는 송출 X
            val deviceQueuedAdIds = (queueByDevice[d.deviceId] ?: emptyList())
                .map { it.id.adId }.toSet()
            val currentAd = if (online) {
                latestStartedByDevice[d.deviceId]?.let { ev ->
                    if (ev.adId !in deviceQueuedAdIds) return@let null
                    val ad = adsById[ev.adId] ?: return@let null
                    if (ad.computeStatus() != AdStatus.ACTIVE) {
                        return@let null
                    }
                    CurrentAdDto(
                        adId = ad.id,
                        title = ad.title,
                        videoFilename = ad.videoFilename,
                        startedAt = ev.occurredAt,
                    )
                }
            } else null

            DeviceListItem(
                deviceId = d.deviceId,
                deviceName = d.deviceName,
                registeredAt = d.registeredAt,
                lastSeenAt = d.lastSeenAt,
                currentRestaurant = if (a != null && r != null) {
                    CurrentRestaurantDto(
                        restaurantId = r.restaurantId,
                        restaurantName = r.name,
                        address = r.address,
                        assignedAt = a.assignedAt,
                    )
                } else null,
                queuedAds = queuedAds,
                online = online,
                currentAd = currentAd,
            )
        }
    }

    companion object {
        /** 최근 활동(play-event / lastSeenAt) 기준 online 판정 fallback 윈도우.
         *  주 신호는 SSE 연결(`isDeviceConnected`) — keepalive 10s 가 dead TCP
         *  를 빠르게 감지하므로 lastSeenAt 은 SSE 가 정상이 아닌 엣지 케이스
         *  (디바이스가 SSE 끊겼는데 play-event 만 보내는 등) 의 보완. 60s 면
         *  광고 길이(보통 ~30s) 의 2배 — 정상 송출 디바이스는 항상 fresh. */
        const val LIVENESS_WINDOW_SECONDS: Long = 60L
        /** "지금 재생 중인 광고" 추론 윈도우. PlayerClient 가 loop=false +
         *  cycleCount 로 매 광고 종료마다 STARTED 를 다시 발사하므로 광고
         *  길이(~30s) 의 4배인 120s 면 정상 송출 중인 디바이스는 항상 윈도우
         *  안에 들어온다. 광고 시작 후 디바이스가 stuck/슬립되어 다음
         *  STARTED 가 안 오는 케이스는 120s 안에 currentAd 에서 빠져 "송출
         *  대기" 또는 "오프라인" 으로 정확히 표시된다. */
        const val CURRENT_AD_WINDOW_SECONDS: Long = 120L
    }
}
