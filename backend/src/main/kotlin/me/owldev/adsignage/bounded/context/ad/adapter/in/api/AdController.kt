package me.owldev.adsignage.bounded.context.ad.adapter.`in`.api

import jakarta.validation.Valid
import me.owldev.adsignage.auth.jwt.AdvertiserPrincipal
import me.owldev.adsignage.bounded.context.ad.application.port.out.database.AdRepositoryPort
import me.owldev.adsignage.bounded.context.ad.application.service.AdService
import me.owldev.adsignage.bounded.context.ad.domain.dto.AdDeploymentItem
import me.owldev.adsignage.bounded.context.ad.domain.dto.AdResponse
import me.owldev.adsignage.bounded.context.ad.domain.dto.CreateAdRequest
import me.owldev.adsignage.bounded.context.ad.domain.dto.UpdateAdScheduleRequest
import me.owldev.adsignage.bounded.context.device.application.port.out.database.DeviceRepositoryPort
import me.owldev.adsignage.bounded.context.playevent.application.port.out.database.PlayEventRepositoryPort
import me.owldev.adsignage.bounded.context.playevent.domain.model.PlayEventType
import me.owldev.adsignage.bounded.context.queue.application.port.out.database.DeviceAdQueueRepositoryPort
import me.owldev.adsignage.bounded.context.restaurant.application.port.out.database.RestaurantRepositoryPort
import me.owldev.adsignage.bounded.context.advertiser.domain.model.AdvertiserRole
import me.owldev.adsignage.bounded.context.assignment.application.port.out.database.DeviceAssignmentRepositoryPort
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
 * [me.owldev.adsignage.bounded.context.ad.domain.model.Ad] 집계(aggregate)를 위한 REST 엔드포인트.
 *
 * AC 3의 Sub-AC 2 범위 — `PUT /api/ads/{id}/schedule`:
 *  - **PUT과 PATCH 모두 허용.** 어느 동사든 *완전한* 스케줄 교체를 보냄.
 *  - **JWT 필수.** [AdvertiserPrincipal]에서 검증된 광고주 id 가 서비스로
 *    전달되고, 서비스가 소유권 인식 조회를 수행하므로 크로스 광고주 id
 *    추측은 404로 매핑됨.
 *
 * HTTP 계약:
 *  - 200 OK            성공 시 (응답 본문 = AdResponse)
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
     * 포트들. mutation 은 없으므로 service 를 거치지 않고 controller 가
     * 직접 join 한다 — 현재 데모 단계의 단순 read 모음.
     */
    private val queueRepositoryPort: DeviceAdQueueRepositoryPort,
    private val deviceRepositoryPort: DeviceRepositoryPort,
    private val assignmentRepository: DeviceAssignmentRepositoryPort,
    private val restaurantRepositoryPort: RestaurantRepositoryPort,
    private val playEventRepositoryPort: PlayEventRepositoryPort,
    @Suppress("unused") private val adRepositoryPort: AdRepositoryPort,
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
     * 광고 목록을 최신 순으로 반환.
     *
     * - ADVERTISER: 자기 광고만 (소유권 격리 유지)
     * - OPERATOR: 모든 광고주의 광고 (디바이스 큐 picker 에서 다른 광고주
     *   광고도 골라 담을 수 있도록)
     */
    @GetMapping(produces = [MediaType.APPLICATION_JSON_VALUE])
    fun listMyAds(
        @AuthenticationPrincipal principal: AdvertiserPrincipal,
    ): ResponseEntity<List<AdResponse>> {
        val ads = if (principal.role == AdvertiserRole.OPERATOR) {
            adService.listAll()
        } else {
            adService.listOwned(principal.advertiserId)
        }
        log.info(
            "GET /api/ads advertiserId={} role={} returning {} ad(s)",
            principal.advertiserId, principal.role, ads.size,
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

        val queueRows = queueRepositoryPort.findAllByIdAdId(adId)
        if (queueRows.isEmpty()) return ResponseEntity.ok(emptyList())

        val deviceIds = queueRows.map { it.id.deviceId }
        val devicesById = deviceRepositoryPort.findAllById(deviceIds).associateBy { it.deviceId }
        val activeAssignments = assignmentRepository.findAllByActiveTrue()
            .filter { it.deviceId in deviceIds }
            .associateBy { it.deviceId }
        val restaurantsById = restaurantRepositoryPort.findAll().associateBy { it.restaurantId }

        // "지금 이 광고 재생 중" 판단 — 최근 5분 안의 STARTED 이벤트 중
        // adId 일치하는 게 있는 디바이스만.
        val recentStartedAds = playEventRepositoryPort
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
     * 본문은 여전히 세 스케줄 필드 모두를 운반해야 함.
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

    private fun updateSchedule(
        adId: String,
        body: UpdateAdScheduleRequest,
        principal: AdvertiserPrincipal,
        verb: String,
    ): ResponseEntity<AdResponse> {
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
