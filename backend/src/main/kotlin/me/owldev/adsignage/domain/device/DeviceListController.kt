package me.owldev.adsignage.domain.device

import com.fasterxml.jackson.annotation.JsonInclude
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import me.owldev.adsignage.domain.ad.AdRepository
import me.owldev.adsignage.domain.ad.computeStatus
import me.owldev.adsignage.domain.assignment.DeviceAssignmentRepository
import me.owldev.adsignage.domain.queue.DeviceAdQueueRepository
import me.owldev.adsignage.domain.restaurant.RestaurantRepository
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
            it.lastSeenAt = Instant.now()
            // device_name 은 처음 생성된 값을 유지(클라이언트가 매번 갱신하면 운영자 라벨이
            // 덮어 쓰일 수 있으므로). 명시적 patch 가 필요하면 별도 엔드포인트.
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
        val adsById = if (queuedAdIds.isEmpty()) {
            emptyMap()
        } else {
            adRepository.findAllById(queuedAdIds).associateBy { it.id }
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
            DeviceListItem(
                deviceId = d.deviceId,
                deviceName = d.deviceName,
                registeredAt = d.registeredAt,
                currentRestaurant = if (a != null && r != null) {
                    CurrentRestaurantDto(
                        restaurantId = r.restaurantId,
                        restaurantName = r.name,
                        address = r.address,
                        assignedAt = a.assignedAt,
                    )
                } else null,
                queuedAds = queuedAds,
            )
        }
        return ResponseEntity.ok(items)
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
    val currentRestaurant: CurrentRestaurantDto?,
    val queuedAds: List<QueuedAdSummary> = emptyList(),
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
