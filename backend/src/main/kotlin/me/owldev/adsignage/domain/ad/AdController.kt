package me.owldev.adsignage.domain.ad

import jakarta.validation.Valid
import me.owldev.adsignage.auth.jwt.AdvertiserPrincipal
import me.owldev.adsignage.domain.ad.dto.AdResponse
import me.owldev.adsignage.domain.ad.dto.CreateAdRequest
import me.owldev.adsignage.domain.ad.dto.UpdateAdScheduleRequest
import me.owldev.adsignage.domain.assignment.DeviceAssignmentRepository
import me.owldev.adsignage.bounded.context.device.application.port.out.database.DeviceRepositoryPort
import me.owldev.adsignage.bounded.context.playevent.application.port.out.database.PlayEventRepositoryPort
import me.owldev.adsignage.bounded.context.playevent.domain.model.PlayEventType
import me.owldev.adsignage.bounded.context.queue.application.port.out.database.DeviceAdQueueRepositoryPort
import me.owldev.adsignage.bounded.context.restaurant.application.port.out.database.RestaurantRepositoryPort
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * [Ad] 집계(aggregate)를 위한 REST 엔드포인트.
 *
 * AC 3의 Sub-AC 2 범위 — `PUT /api/ads/{id}/schedule`:
 *  - **PUT과 PATCH 모두 허용.** 해커톤 AC가 "PUT/PATCH"로 명명; 관리자 UI
 *    클라이언트가 405 없이 어느 동사든 선택할 수 있도록 둘 다 등록.
 *    어느 동사든 *완전한* 스케줄 교체를 보냄 — 서비스가 세 필드 모두를
 *    덮어쓰므로 시맨틱적으로 이는 PUT이지만, 관례적인 부분 업데이트 동사를
 *    선호하는 클라이언트를 위해 PATCH가 허용됨.
 *  - **JWT 필수.** 이 메서드가 실행되기 전에 스프링 시큐리티가 미인증
 *    요청을 거부; 따라서 [AdvertiserPrincipal] 인자는 non-null이 보장됨.
 *    검증된 광고주 id가 서비스로 전달되고, 서비스가 소유권 인식 조회를
 *    수행하므로 크로스 광고주 id 추측은 404로 매핑됨.
 *  - **검증.** 필드 수준 제약(`@NotNull`, `@Min`, `@Max`, `HH:mm` 파싱)은
 *    [UpdateAdScheduleRequest]에 위치하며 `@Valid`에 의해 트리거됨. 크로스
 *    필드 규칙 "endTime > startTime"은 서비스에서 강제되어 Bean Validation
 *    모양과 일치하는 `fieldErrors` 맵과 함께 400으로 표면화됨 —
 *    [InvalidScheduleException]에 대한 [me.owldev.adsignage.web.GlobalExceptionHandler]
 *    매핑 참조.
 *
 * HTTP 계약:
 *  - 200 OK            성공 시 (응답 본문 = [AdResponse])
 *  - 400 Bad Request   필드 검증 실패 또는 잘못된 스케줄 윈도우
 *  - 401 Unauthorized  JWT가 없거나 잘못된 경우(SecurityConfig가 처리)
 *  - 404 Not Found     광고 id가 알려지지 않았거나 호출자가 소유하지 않은 경우
 */
@RestController
@RequestMapping("/api/ads")
class AdController(
    private val adService: AdService,
    /**
     * 광고주가 read-only 로 "이 광고가 어디 송출 중인지" 보기 위한 조회용
     * repository 들. mutation 은 없으므로 service 를 거치지 않고 controller 가
     * 직접 join 한다 — 현재 데모 단계의 단순 read 모음.
     */
    private val queueRepository: DeviceAdQueueRepositoryPort,
    private val deviceRepository: DeviceRepositoryPort,
    private val assignmentRepository: DeviceAssignmentRepository,
    private val restaurantRepository: RestaurantRepositoryPort,
    private val playEventRepository: PlayEventRepositoryPort,
) {

    private val log = LoggerFactory.getLogger(AdController::class.java)

    /**
     * 새 광고 생성. 본문에는 영상 파일명 + 제목 + 일일 스케줄을 한 번에
     * 담아 보낸다. 백엔드는 광고 행을 만들고 PLAYLIST_UPDATE SSE 이벤트를
     * 트랜잭션 커밋 후 발행한다.
     */
    @PostMapping(
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    fun createAd(
        @Valid @RequestBody body: CreateAdRequest,
        @AuthenticationPrincipal principal: AdvertiserPrincipal,
    ): ResponseEntity<AdResponse> {
        val title: String = body.title!!.trim()
        val videoFilename: String = body.videoFilename!!.trim()
        val startTime: LocalTime = body.startTime!!
        val endTime: LocalTime = body.endTime!!
        val dailyPlayCount: Int = body.dailyPlayCount!!
        val campaignStartDate: LocalDate = body.campaignStartDate!!
        val campaignEndDate: LocalDate = body.campaignEndDate!!

        log.info(
            "POST /api/ads advertiserId={} title='{}' videoFilename={} startTime={} endTime={} dailyPlayCount={} campaign={}..{}",
            principal.advertiserId, title, videoFilename, startTime, endTime, dailyPlayCount,
            campaignStartDate, campaignEndDate,
        )

        val saved = adService.create(
            advertiserId = principal.advertiserId,
            title = title,
            videoFilename = videoFilename,
            startTime = startTime,
            endTime = endTime,
            dailyPlayCount = dailyPlayCount,
            campaignStartDate = campaignStartDate,
            campaignEndDate = campaignEndDate,
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(AdResponse.from(saved))
    }

    /**
     * 호출 광고주가 소유한 광고 목록을 최신 순으로 반환. 어드민의
     * `/ads` 페이지가 사용.
     */
    @GetMapping(produces = [MediaType.APPLICATION_JSON_VALUE])
    fun listMyAds(
        @AuthenticationPrincipal principal: AdvertiserPrincipal,
    ): ResponseEntity<List<AdResponse>> {
        val ads = adService.listOwned(principal.advertiserId)
        log.info(
            "GET /api/ads advertiserId={} returning {} ad(s)",
            principal.advertiserId, ads.size,
        )
        return ResponseEntity.ok(ads.map(AdResponse::from))
    }

    /**
     * 단일 광고 조회. 호출 광고주가 소유하지 않은 광고는 404로 매핑.
     * 어드민의 `/ads/{id}` 스케줄 편집 페이지가 기존 스케줄을 폼에 미리
     * 채우기 위해 사용한다.
     */
    @GetMapping(
        "/{id}",
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    fun getAd(
        @PathVariable("id") adId: String,
        @AuthenticationPrincipal principal: AdvertiserPrincipal,
    ): ResponseEntity<AdResponse> {
        val ad = adService.findOwned(adId, principal.advertiserId)
        return ResponseEntity.ok(AdResponse.from(ad))
    }

    /**
     * "이 광고가 송출 중인 디바이스 현황" — 광고주가 자기 광고가 어디에
     * 깔려 있는지 read-only 로 조회. mutation 권한이 OPERATOR 로 분리된
     * RBAC 전환 후 광고주의 가시성을 보전하는 통로.
     *
     * 응답에는 큐에 담긴 디바이스마다:
     *   - deviceId / deviceName / restaurantName
     *   - currentlyPlaying : 그 디바이스가 *지금 이 광고를 송출 중*인지
     *     (최근 5분 내 STARTED 이벤트가 이 광고에 대해 있는지로 판단)
     */
    @GetMapping(
        "/{id}/deployments",
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    fun getAdDeployments(
        @PathVariable("id") adId: String,
        @AuthenticationPrincipal principal: AdvertiserPrincipal,
    ): ResponseEntity<List<AdDeploymentItem>> {
        // 소유 확인 — 다른 광고주 id 추측 시 404 로 통일.
        adService.findOwned(adId, principal.advertiserId)

        val queueRows = queueRepository.findAllByIdAdId(adId)
        if (queueRows.isEmpty()) return ResponseEntity.ok(emptyList())

        val deviceIds = queueRows.map { it.id.deviceId }
        val devicesById = deviceRepository.findAllById(deviceIds).associateBy { it.deviceId }
        val activeAssignments = assignmentRepository.findAllByActiveTrue()
            .filter { it.deviceId in deviceIds }
            .associateBy { it.deviceId }
        val restaurantsById = restaurantRepository.findAll().associateBy { it.restaurantId }

        // "지금 이 광고 재생 중" 판단 — 최근 5분 안의 STARTED 이벤트 중
        // adId 일치하는 게 있는 디바이스만.
        val recentStartedAds = playEventRepository
            .findLatestPerDeviceByEventTypeSince(
                PlayEventType.STARTED,
                Instant.now().minusSeconds(300),
            )
            .filter { it.adId == adId }
            .map { it.deviceId }
            .toSet()

        val items = queueRows.mapNotNull { q ->
            val device = devicesById[q.id.deviceId] ?: return@mapNotNull null
            val assignment = activeAssignments[device.deviceId]
            val restaurant = assignment?.let { restaurantsById[it.restaurantId] }
            AdDeploymentItem(
                deviceId = device.deviceId,
                deviceName = device.deviceName,
                restaurantName = restaurant?.name,
                addedAt = q.addedAt,
                currentlyPlaying = device.deviceId in recentStartedAds,
            )
        }
        return ResponseEntity.ok(items)
    }

    /**
     * 광고 삭제. 소유자만 삭제 가능 — 다른 광고주 id 를 추측해도 404.
     * 삭제 후 PLAYLIST_UPDATE 이벤트가 AFTER_COMMIT 에 발행되어, 그 광고를
     * 송출 중이던 디바이스들이 SSE 로 즉시 새 플레이리스트를 받는다.
     */
    @DeleteMapping("/{id}")
    fun deleteAd(
        @PathVariable("id") adId: String,
        @AuthenticationPrincipal principal: AdvertiserPrincipal,
    ): ResponseEntity<Void> {
        log.info("DELETE /api/ads/{} advertiserId={}", adId, principal.advertiserId)
        adService.delete(adId, principal.advertiserId)
        return ResponseEntity.noContent().build()
    }

    /**
     * 광고 [id]에 대한 스케줄을 업데이트. PUT 시맨틱 — 호출자가 완전한
     * 스케줄(시작, 종료, 일일 횟수)을 보내고 서버가 행의 스케줄 필드를
     * 통째로 교체.
     */
    @PutMapping(
        "/{id}/schedule",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    fun putSchedule(
        @PathVariable("id") adId: String,
        @Valid @RequestBody body: UpdateAdScheduleRequest,
        @AuthenticationPrincipal principal: AdvertiserPrincipal,
    ): ResponseEntity<AdResponse> = updateSchedule(adId, body, principal, "PUT")

    /**
     * PATCH 동사 아래의 [putSchedule] 형제. 동일한 와이어 계약 — 요청
     * 본문은 여전히 세 스케줄 필드 모두를 운반해야 함. "부분 모양"
     * 리소스 변경에 PATCH를 선호하는 클라이언트를 위해 제공.
     */
    @PatchMapping(
        "/{id}/schedule",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    fun patchSchedule(
        @PathVariable("id") adId: String,
        @Valid @RequestBody body: UpdateAdScheduleRequest,
        @AuthenticationPrincipal principal: AdvertiserPrincipal,
    ): ResponseEntity<AdResponse> = updateSchedule(adId, body, principal, "PATCH")

    // -------------------------------------------------------------------------
    // 내부 구현
    // -------------------------------------------------------------------------

    private fun updateSchedule(
        adId: String,
        body: UpdateAdScheduleRequest,
        principal: AdvertiserPrincipal,
        verb: String,
    ): ResponseEntity<AdResponse> {
        // Bean Validation이 이미 이것들이 non-null임을 증명했음; 여기서의
        // !!는 서비스 시그니처가 non-nullable 타입을 받고 검증 후 불변식을
        // 반영할 수 있도록 하는 안전한 추출.
        val startTime: LocalTime = body.startTime!!
        val endTime: LocalTime = body.endTime!!
        val dailyPlayCount: Int = body.dailyPlayCount!!

        log.info(
            "{} /api/ads/{}/schedule advertiserId={} startTime={} endTime={} dailyPlayCount={}",
            verb, adId, principal.advertiserId, startTime, endTime, dailyPlayCount,
        )

        val saved = adService.updateSchedule(
            adId = adId,
            advertiserId = principal.advertiserId,
            startTime = startTime,
            endTime = endTime,
            dailyPlayCount = dailyPlayCount,
            campaignStartDate = body.campaignStartDate,
            campaignEndDate = body.campaignEndDate,
        )
        return ResponseEntity.ok(AdResponse.from(saved))
    }
}

/**
 * 광고주가 자기 광고가 어떤 디바이스에 깔렸는지 read-only 로 보는 응답 항목.
 * `currentlyPlaying` 은 최근 5분 내 STARTED 이벤트가 그 광고에 대해 있는지로
 * 판단 — 디바이스가 그 순간 *그 광고* 를 화면에 띄우고 있다는 신호.
 */
data class AdDeploymentItem(
    val deviceId: String,
    val deviceName: String,
    val restaurantName: String?,
    @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
    val addedAt: Instant,
    val currentlyPlaying: Boolean,
)
