package me.owldev.adsignage.domain.playlist

import com.fasterxml.jackson.annotation.JsonFormat
import me.owldev.adsignage.domain.ad.AdRepository
import me.owldev.adsignage.domain.ad.AdStatus
import me.owldev.adsignage.domain.ad.computeStatus
import me.owldev.adsignage.domain.assignment.DeviceAssignmentRepository
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.time.LocalTime

/**
 * 디바이스가 부팅 후/SSE 이벤트마다 호출하는 플레이리스트 엔드포인트.
 *
 *   GET /api/devices/{deviceId}/playlist
 *     공개(JWT 없음) — 디바이스가 토큰을 갖지 않으므로 SecurityConfig 에서
 *     이미 permitAll 로 화이트리스트되어 있다.
 *
 * 계산:
 *   1. device → 활성 매핑(restaurant_id) 조회. 매핑이 없으면 빈 ads 반환
 *      → 플레이어가 splash.png 표시.
 *   2. 매핑이 있으면 *모든 광고주*의 광고 중 캠페인 기간이 ACTIVE 인 것만
 *      모아 반환. (해커톤 모델에서 광고는 음식점 화이트리스트가 없고, 매핑된
 *      디바이스 전체에 라운드 로빈으로 분배된다.)
 *   3. 일일 시간 윈도우(start_time / end_time)와 dailyPlayCount 는 응답에
 *      그대로 실어 보내고 시간 필터는 플레이어가 책임진다 — 그래야 자정 경계
 *      처리, 하루 안 카운팅, day-rollover 등이 디바이스 로컬 시각 기준으로
 *      정확하게 동작한다.
 */
@RestController
class PlaylistController(
    private val adRepository: AdRepository,
    private val assignmentRepository: DeviceAssignmentRepository,
) {
    private val log = LoggerFactory.getLogger(PlaylistController::class.java)

    @GetMapping(
        "/api/devices/{deviceId}/playlist",
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    @Transactional(readOnly = true)
    fun getPlaylist(@PathVariable deviceId: String): ResponseEntity<DevicePlaylistResponse> {
        val now = Instant.now()
        val assignment = assignmentRepository.findByDeviceIdAndActiveTrue(deviceId).orElse(null)
        val restaurantId = assignment?.restaurantId

        val ads: List<PlaylistAdResponse> =
            if (restaurantId == null) {
                emptyList()
            } else {
                adRepository.findAll()
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
            "GET /api/devices/{}/playlist restaurantId={} ads={}",
            deviceId, restaurantId, ads.size,
        )
        return ResponseEntity.ok(
            DevicePlaylistResponse(
                deviceId = deviceId,
                restaurantId = restaurantId,
                ads = ads,
                fetchedAt = now,
            ),
        )
    }
}

data class DevicePlaylistResponse(
    val deviceId: String,
    val restaurantId: String?,
    val ads: List<PlaylistAdResponse>,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val fetchedAt: Instant,
)

data class PlaylistAdResponse(
    val adId: String,
    val title: String,
    val videoUrl: String,
    val scheduleId: String,
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "HH:mm")
    val startTime: LocalTime,
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "HH:mm")
    val endTime: LocalTime,
    val dailyCount: Int,
)
