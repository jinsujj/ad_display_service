package me.owldev.adsignage.domain.device

import com.fasterxml.jackson.annotation.JsonInclude
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import me.owldev.adsignage.domain.ad.AdRepository
import me.owldev.adsignage.domain.assignment.DeviceAssignmentRepository
import me.owldev.adsignage.domain.playevent.PlayEventRepository
import me.owldev.adsignage.domain.playevent.PlayEventType
import me.owldev.adsignage.domain.queue.DeviceAdQueueRepository
import me.owldev.adsignage.domain.restaurant.RestaurantRepository
import me.owldev.adsignage.sse.SseEmitterRegistry
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

/**
 * 디바이스 등록 + 목록 조회 엔드포인트.
 *
 *   POST /api/devices/register
 *     안드로이드 광고판이 부팅 시 자기 device_id 를 신고. 인증 없음 — 디바이스가
 *     JWT 를 갖지 않으므로 공개 엔드포인트 (SecurityConfig 에 permitAll 추가됨).
 *     멱등 — 이미 등록된 device_id 면 last_seen_at 만 갱신.
 *
 *   GET /api/devices
 *     광고주/운영자 어드민이 보는 디바이스 목록. 활성 매핑이 있으면 음식점
 *     정보도 nested 로 포함. JWT 필요.
 *
 *   GET /api/restaurants
 *     매핑 드롭다운용 음식점 목록. JWT 필요.
 */
@RestController
class DeviceListController(
    private val deviceRepository: DeviceRepository,
    private val assignmentRepository: DeviceAssignmentRepository,
    private val restaurantRepository: RestaurantRepository,
    private val queueRepository: DeviceAdQueueRepository,
    private val adRepository: AdRepository,
    /**
     * 어드민 모니터링 화면이 "이 디바이스가 지금 살아있나" + "지금 어떤 광고를
     * 송출 중인가" 를 알려주려면 두 가지 신호가 필요하다.
     *
     *   - SseEmitterRegistry.isDeviceConnected: SSE 연결의 살아있음 — 가장
     *     정확한 online 신호. keepalive 가 30초마다 phantom 연결을 청소한다.
     *   - PlayEventRepository.findLatestPerDeviceByEventTypeSince: 최근 90초
     *     안의 STARTED 이벤트로 "지금 재생 중" 광고를 batch lookup.
     */
    private val playEventRepository: PlayEventRepository,
    private val sseRegistry: SseEmitterRegistry,
) {
    private val log = LoggerFactory.getLogger(DeviceListController::class.java)

    @PostMapping(
        "/api/devices/register",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    @Transactional
    fun register(@Valid @RequestBody body: RegisterDeviceRequest): ResponseEntity<RegisterDeviceResponse> {
        val deviceId = body.deviceId!!.trim()
        val deviceName = body.deviceName?.trim()?.takeIf { it.isNotEmpty() }
            ?: "Device-${deviceId.take(8)}"

        val saved = deviceRepository.findById(deviceId).orElse(null)?.also {
            // 도메인 메서드 호출 — 시각 결정/필드 변경은 Device 가 책임.
            // device_name 은 처음 생성된 값을 유지(클라이언트가 매번 갱신하면 운영자 라벨이
            // 덮어 쓰일 수 있으므로). 명시적 patch 가 필요하면 별도 엔드포인트.
            it.touch()
        } ?: deviceRepository.save(
            Device(
                deviceId = deviceId,
                deviceName = deviceName,
                lastSeenAt = Instant.now(),
            ),
        )

        log.info("device registered/seen deviceId={} name={}", saved.deviceId, saved.deviceName)
        return ResponseEntity.status(HttpStatus.CREATED).body(
            RegisterDeviceResponse(
                deviceId = saved.deviceId,
                deviceName = saved.deviceName,
                registeredAt = saved.registeredAt,
                lastSeenAt = saved.lastSeenAt,
            ),
        )
    }

    /**
     * 디바이스 heartbeat — PlayerClient 가 5초마다 호출. lastSeenAt 만 갱신
     * 하므로 가벼움(write 1회). Android 앱이 sendBeacon 도 못 보내고 SSE TCP
     * 도 nginx idle 안에 갇혀 죽는 케이스에서, 마지막 heartbeat 가 윈도우
     * 밖으로 나가면 즉시 오프라인 판정. 인증 없음 — 디바이스에 JWT 없음.
     */
    @PostMapping("/api/devices/{deviceId}/heartbeat")
    @Transactional
    fun heartbeat(@PathVariable deviceId: String): ResponseEntity<Void> {
        deviceRepository.findById(deviceId).orElse(null)?.also {
            it.touch()
            deviceRepository.save(it)
        }
        return ResponseEntity.noContent().build()
    }

    /**
     * 디바이스가 종료/슬립으로 들어가면서 자기 자신을 즉시 오프라인으로
     * 알리는 신호. 인증 없음 — 디바이스가 토큰을 갖지 않으므로. 호출 효과:
     *   1) SSE 연결 강제 close → online 즉시 false
     *   2) lastSeenAt 을 90초 윈도우 밖으로 강제(현재시각 - 91s) → 어드민
     *      모니터의 다음 폴링(1.5s)에서 즉시 오프라인 카드로 표시
     *
     * Android WebView 가 destroy 되면서 발사되는 `pagehide` 이벤트에서
     * `navigator.sendBeacon` 으로 호출 — 비동기 보장 + 페이지 종료 중에도
     * 안전하게 전송됨.
     */
    @PostMapping("/api/devices/{deviceId}/offline")
    @Transactional
    fun reportOffline(@PathVariable deviceId: String): ResponseEntity<Void> {
        sseRegistry.forceCloseAll(deviceId)
        deviceRepository.findById(deviceId).orElse(null)?.also {
            // 90초 윈도우(LIVENESS_WINDOW_SECONDS) 보다 더 오래 전으로 강제.
            it.lastSeenAt = Instant.now().minusSeconds(LIVENESS_WINDOW_SECONDS + 1)
            deviceRepository.save(it)
        }
        log.info("device offline reported deviceId={}", deviceId)
        return ResponseEntity.noContent().build()
    }

    /**
     * 어드민이 디바이스를 제거. device 행 + 활성/비활성 매핑 이력까지 일괄
     * 삭제한다. 디바이스 앱이 다시 켜지면 멱등 register 가 새 행을 만들어
     * 다시 등록되므로 재시작이나 분실/교체 디바이스 정리에 안전하다.
     */
    @DeleteMapping("/api/devices/{deviceId}")
    @Transactional
    fun deleteDevice(@PathVariable deviceId: String): ResponseEntity<Void> {
        // 큐 + 매핑 이력 먼저 (외래 데이터 모두 정리)
        val removedQueue = queueRepository.deleteAllByDeviceId(deviceId)
        val removedAssignments = assignmentRepository.deleteAllByDeviceId(deviceId)
        // 그 다음 디바이스 자체. 없는 id 면 deleteById가 EmptyResultDataAccessException
        // 을 던지므로 existsById 로 가드.
        val existed = deviceRepository.existsById(deviceId)
        if (existed) deviceRepository.deleteById(deviceId)
        log.info(
            "DELETE /api/devices/{} removed_device={} removed_queue={} removed_assignments={}",
            deviceId, existed, removedQueue, removedAssignments,
        )
        return ResponseEntity.noContent().build()
    }

    /**
     * 단일 디바이스 상세 + 매핑 이력 (활성/비활성 모두, 최신순).
     * /devices/{deviceId} 페이지가 사용.
     */
    @GetMapping(
        "/api/devices/{deviceId}",
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    @Transactional(readOnly = true)
    fun getDeviceDetail(@PathVariable deviceId: String): ResponseEntity<DeviceDetailResponse> {
        val device = deviceRepository.findById(deviceId).orElse(null)
            ?: return ResponseEntity.notFound().build()

        val assignmentsRaw = assignmentRepository.findAllByDeviceIdOrderByAssignedAtDesc(deviceId)
        val restaurantsById = restaurantRepository.findAll().associateBy { it.restaurantId }

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

        return ResponseEntity.ok(
            DeviceDetailResponse(
                deviceId = device.deviceId,
                deviceName = device.deviceName,
                registeredAt = device.registeredAt,
                lastSeenAt = device.lastSeenAt,
                currentAssignment = current,
                history = history,
            ),
        )
    }

    @GetMapping(
        "/api/devices",
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    @Transactional(readOnly = true)
    fun listDevices(): ResponseEntity<List<DeviceListItem>> {
        val devices = deviceRepository.findAllByOrderByRegisteredAtDesc()
        if (devices.isEmpty()) return ResponseEntity.ok(emptyList())

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
        val restaurantsById = restaurantRepository.findAll().associateBy { it.restaurantId }

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
            adRepository.findAllById(allAdIds).associateBy { it.id }
        }

        val items = devices.map { d ->
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

            // currentAd 는 디바이스가 online 일 때만 의미가 있다. 오프라인이면
            // 마지막 STARTED 가 stale 일 가능성이 커서 의도적으로 null 처리.
            val currentAd = if (online) {
                latestStartedByDevice[d.deviceId]?.let { ev ->
                    val ad = adsById[ev.adId] ?: return@let null
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
        return ResponseEntity.ok(items)
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

@RestController
@RequestMapping("/api/restaurants")
class RestaurantListController(
    private val restaurantRepository: RestaurantRepository,
) {
    @GetMapping(produces = [MediaType.APPLICATION_JSON_VALUE])
    @Transactional(readOnly = true)
    fun list(): ResponseEntity<List<RestaurantListItem>> {
        val items = restaurantRepository.findAllByOrderByName().map {
            RestaurantListItem(
                restaurantId = it.restaurantId,
                restaurantName = it.name,
                address = it.address,
            )
        }
        return ResponseEntity.ok(items)
    }
}

/* -------- DTOs -------- */

data class RegisterDeviceRequest(
    @field:NotBlank(message = "deviceId must not be blank")
    @field:Size(max = 36, message = "deviceId must be at most 36 characters")
    val deviceId: String?,

    @field:Size(max = 255, message = "deviceName must be at most 255 characters")
    val deviceName: String? = null,
)

data class RegisterDeviceResponse(
    val deviceId: String,
    val deviceName: String,
    val registeredAt: Instant,
    val lastSeenAt: Instant?,
)

@JsonInclude(JsonInclude.Include.ALWAYS)
data class DeviceListItem(
    val deviceId: String,
    val deviceName: String,
    val registeredAt: Instant,
    /** 마지막으로 디바이스 활동(register / play-event) 이 관측된 시각. 모니터링
     *  카드의 "마지막 활동 N분 전" 라벨이 사용. null 이면 아직 한 번도 안 본 것. */
    val lastSeenAt: Instant?,
    val currentRestaurant: CurrentRestaurantDto?,
    val queuedAds: List<QueuedAdSummary> = emptyList(),
    /**
     * 디바이스가 지금 살아 있는가? SSE 연결 + 최근 90초 내 play-event 두 신호의
     * OR. 어드민 모니터링 카드가 회색/컬러를 결정.
     */
    val online: Boolean = false,
    /**
     * 디바이스가 *현재 송출 중* 인 광고. STARTED 이벤트 기반이므로 서버
     * 진실이며, 클라이언트가 큐를 시뮬레이션한 결과가 아니다. 오프라인이거나
     * 90초 내 STARTED 가 없으면 null.
     */
    val currentAd: CurrentAdDto? = null,
)

/**
 * 어드민 디바이스 탭의 모니터링 썸네일용 가벼운 광고 요약. 풀 광고 메타가
 * 필요하면 디바이스 상세에서 `GET /api/devices/{id}/ads` 사용.
 */
data class QueuedAdSummary(
    val adId: String,
    val title: String,
    val videoFilename: String,
    /** SCHEDULED / ACTIVE / EXPIRED — Ad.computeStatus() 결과. */
    val status: String,
)

/**
 * "지금 디바이스가 송출 중인 광고" 의 최소 메타. 모니터 카드에 동일한 영상을
 * autoplay 로 미러링하는 데 필요한 만큼만 운반.
 */
data class CurrentAdDto(
    val adId: String,
    val title: String,
    val videoFilename: String,
    /** 디바이스가 광고를 시작했다고 보고한 시각(occurredAt). 운영자 UI 가
     *  "▶ 0:24 째 송출 중" 같은 경과 시간 라벨에 사용 가능. */
    val startedAt: Instant,
)

data class CurrentRestaurantDto(
    val restaurantId: String,
    val restaurantName: String,
    val address: String?,
    val assignedAt: Instant,
)

data class RestaurantListItem(
    val restaurantId: String,
    val restaurantName: String,
    val address: String?,
)

data class DeviceDetailResponse(
    val deviceId: String,
    val deviceName: String,
    val registeredAt: Instant,
    val lastSeenAt: Instant?,
    val currentAssignment: AssignmentHistoryItem?,
    val history: List<AssignmentHistoryItem>,
)

data class AssignmentHistoryItem(
    val assignmentId: String,
    val restaurantId: String,
    val restaurantName: String,
    val address: String?,
    val assignedAt: Instant,
    val active: Boolean,
)
