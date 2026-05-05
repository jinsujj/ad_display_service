package me.owldev.adsignage.bounded.context.ad.application.service

import me.owldev.adsignage.bounded.context.ad.application.port.out.database.AdRepositoryPort
import me.owldev.adsignage.bounded.context.ad.domain.exception.AdNotFoundException
import me.owldev.adsignage.bounded.context.ad.domain.model.Ad
import me.owldev.adsignage.bounded.context.queue.application.port.out.database.DeviceAdQueueRepositoryPort
import me.owldev.adsignage.bounded.context.playlist.adapter.out.sse.PlaylistUpdatedEvent
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.LocalTime

/**
 * [Ad] 집계(aggregate)의 변경(mutating) 라이프사이클을 소유하는 서비스.
 *
 *  - [updateSchedule] — 호출 광고주가 소유한 광고의 스케줄 필드(startTime,
 *    endTime, dailyPlayCount, campaign window)를 교체.
 *  - [create]         — 새 광고를 영속화하고 PLAYLIST_UPDATE 이벤트 발행.
 *  - [delete]         — 광고를 삭제하고 모든 디바이스 큐에서 제거.
 *  - [findOwned]      — 변경하지 않는 GET 을 위해 컨트롤러가 사용하는 헬퍼.
 *
 * Auth-and-isolation 계약:
 *  - 모든 진입점은 JWT principal 에서 *검증된* `advertiserId` 를 받음. 조회는
 *    [AdRepositoryPort.findByIdAndAdvertiserId] 를 거치므로 광고주는 자신의
 *    광고에만 영향을 줄 수 있음 — 크로스 id 접근은 [AdNotFoundException]
 *    (HTTP 404)으로 축약되어 광고 존재 여부를 절대 누설하지 않음.
 *
 * 크로스 필드 검증:
 *  - DB 의 `ck_ads_time_window` CHECK 가 이미 `endTime <= startTime` 을
 *    거부하지만, 제약 위반 예외가 벤더 특화 메시지를 가진
 *    `DataIntegrityViolation` 으로 버블링됨. 여기서 규칙을 명시적으로
 *    주장(assert)하여 API 가 필드 수준 규칙에 대한 Bean Validation 의 모양과
 *    일치하는 깔끔한 field-error 맵을 반환하게 함. DB 제약은 직접 쓰기에
 *    대한 최후의 가드로 유지됨.
 */
@Service
class AdService(
    private val adRepositoryPort: AdRepositoryPort,
    /**
     * 스케줄 변경 / 생성 / 삭제는 SSE 브리지가 영향받을 수 있는 모든
     * 플레이어로 `PLAYLIST_UPDATE` 이벤트를 팬아웃할 수 있도록 스프링
     * 애플리케이션 이벤트 버스에서 자신을 알림. 리스너는 AFTER_COMMIT 에
     * 묶여 있어 트랜잭션 커밋 후에만 SSE 가 나간다.
     */
    private val eventPublisher: ApplicationEventPublisher,
    /**
     * 광고가 삭제되면 그 광고를 담고 있던 모든 디바이스 큐 행도 함께 정리해
     * dangling reference 가 남지 않도록 한다.
     */
    private val queueRepositoryPort: DeviceAdQueueRepositoryPort,
) {

    private val log = LoggerFactory.getLogger(AdService::class.java)

    /**
     * id가 [adId]이고 소유자가 [advertiserId]인 광고의 스케줄 필드를 교체.
     * 영속화된 [Ad](업데이트된 스케줄 포함)를 반환. 행이 절반만 적용된
     * 스케줄로 관찰 가능하게 남지 않도록 단일 트랜잭션에서 실행됨.
     */
    @Transactional
    fun updateSchedule(
        adId: String,
        advertiserId: String,
        startTime: LocalTime,
        endTime: LocalTime,
        dailyPlayCount: Int,
        campaignStartDate: LocalDate? = null,
        campaignEndDate: LocalDate? = null,
    ): Ad {
        val ad = adRepositoryPort.findByIdAndAdvertiserId(adId, advertiserId)
            ?: run {
                log.info(
                    "updateSchedule: ad not found or not owned (adId={}, advertiserId={})",
                    adId, advertiserId,
                )
                throw AdNotFoundException(adId)
            }

        // 도메인 룰은 entity 가 강제. validation 위반 시 InvalidScheduleException
        // 이 도메인 layer 에서 직접 던져진다.
        ad.changeSchedule(startTime, endTime, dailyPlayCount)
        if (campaignStartDate != null && campaignEndDate != null) {
            ad.changeCampaign(campaignStartDate, campaignEndDate)
        }

        val saved = adRepositoryPort.save(ad)
        log.info(
            "updateSchedule: adId={} advertiserId={} startTime={} endTime={} dailyPlayCount={}",
            saved.id, saved.advertiserId, saved.startTime, saved.endTime, saved.dailyPlayCount,
        )
        publishPlaylistUpdated(saved)
        return saved
    }

    /**
     * id가 [adId]이고 소유자가 [advertiserId]인 광고를 가져오거나, 두 조건
     * 중 하나라도 실패하면 [AdNotFoundException]을 던짐.
     */
    @Transactional(readOnly = true)
    fun findOwned(adId: String, advertiserId: String): Ad =
        adRepositoryPort.findByIdAndAdvertiserId(adId, advertiserId)
            ?: throw AdNotFoundException(adId)

    /** 호출 광고주가 소유한 모든 광고를 최신 순으로 반환. */
    @Transactional(readOnly = true)
    fun listOwned(advertiserId: String): List<Ad> =
        adRepositoryPort.findAllByAdvertiserIdOrderByCreatedAtDesc(advertiserId)

    /**
     * 새 광고 생성. 호출자는 이미 업로드된 영상의 [videoFilename] 을 들고
     * 와서 제목과 스케줄과 함께 묶는다.
     *
     * 한 트랜잭션에서 영속화하고, 커밋 후 PLAYLIST_UPDATE SSE 이벤트를
     * 발행해 디바이스가 새 플레이리스트를 즉시 반영하게 한다.
     */
    @Transactional
    fun create(
        advertiserId: String,
        title: String,
        videoFilename: String,
        startTime: LocalTime,
        endTime: LocalTime,
        dailyPlayCount: Int,
        campaignStartDate: LocalDate,
        campaignEndDate: LocalDate,
    ): Ad {
        // [Ad.create] 팩토리가 모든 도메인 룰을 강제 후 인스턴스를 만든다.
        // 직접 생성자 호출 대신 팩토리를 거치므로 잘못된 광고가 절대 영속화되지 않는다.
        val saved = adRepositoryPort.save(
            Ad.create(
                advertiserId = advertiserId,
                title = title,
                videoFilename = videoFilename,
                startTime = startTime,
                endTime = endTime,
                dailyPlayCount = dailyPlayCount,
                campaignStartDate = campaignStartDate,
                campaignEndDate = campaignEndDate,
            ),
        )
        log.info(
            "create: adId={} advertiserId={} videoFilename={} startTime={} endTime={} dailyPlayCount={}",
            saved.id, saved.advertiserId, saved.videoFilename,
            saved.startTime, saved.endTime, saved.dailyPlayCount,
        )
        publishPlaylistUpdated(saved)
        return saved
    }

    /**
     * id 가 [adId] 이고 [advertiserId] 가 소유한 광고를 삭제. 다른 광고주의
     * 광고 id 를 추측해도 [AdNotFoundException] (404) 으로 동일하게 응답하므로
     * 존재 여부가 누설되지 않는다.
     *
     * 광고 행이 사라지면 그 광고가 들어 있던 모든 디바이스 플레이리스트도
     * 의미가 변하므로 PLAYLIST_UPDATE 이벤트를 AFTER_COMMIT 에 발행한다.
     */
    @Transactional
    fun delete(adId: String, advertiserId: String) {
        val ad = adRepositoryPort.findByIdAndAdvertiserId(adId, advertiserId)
            ?: throw AdNotFoundException(adId)
        // 큐 행을 먼저 정리해야 ads(parent) 삭제 후 dangling 큐 행이 남지 않는다.
        val removedQueueRows = queueRepositoryPort.deleteAllByAdId(adId)
        adRepositoryPort.delete(ad)
        log.info(
            "delete: adId={} advertiserId={} removed_queue_rows={}",
            adId, advertiserId, removedQueueRows,
        )
        publishPlaylistUpdated(ad)
    }

    /**
     * [PlaylistUpdatedEvent] 발행. 잘못 동작하는 이벤트 리스너가 mutation 을
     * 절대 롤백하지 않도록 try/catch 로 감쌈.
     */
    private fun publishPlaylistUpdated(saved: Ad) {
        try {
            eventPublisher.publishEvent(
                PlaylistUpdatedEvent(
                    advertiserId = saved.advertiserId,
                    adId = saved.id,
                ),
            )
        } catch (ex: Exception) {
            log.warn(
                "Failed to publish PlaylistUpdatedEvent for adId={} advertiserId={}: {}",
                saved.id, saved.advertiserId, ex.message,
            )
        }
    }
}
