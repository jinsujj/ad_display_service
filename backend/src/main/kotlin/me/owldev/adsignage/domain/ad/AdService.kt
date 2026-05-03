package me.owldev.adsignage.domain.ad

import me.owldev.adsignage.domain.queue.DeviceAdQueueRepository
import me.owldev.adsignage.sse.PlaylistUpdatedEvent
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.LocalTime

/**
 * [Ad] 집계(aggregate)의 변경(mutating) 라이프사이클을 소유하는 서비스.
 *
 * AC 3의 Sub-AC 2 범위:
 *  - [updateSchedule] — 호출 광고주가 소유한 광고의 스케줄 필드(startTime,
 *    endTime, dailyPlayCount)를 교체.
 *  - [findOwned]      — 변경하지 않는 GET을 위해 컨트롤러가 사용하는 읽기
 *    헬퍼(현재 테스트/추후 엔드포인트를 위해 노출됨).
 *
 * Auth-and-isolation 계약:
 *  - 모든 진입점은 JWT principal에서 *검증된* `advertiserId`를 받음. 조회는
 *    [AdRepository.findByIdAndAdvertiserId]를 거치므로 광고주는 자신의
 *    광고에만 영향을 줄 수 있음 — 크로스 id 접근은 [AdNotFoundException]
 *    (HTTP 404)으로 축약되어 광고 존재 여부를 절대 누설하지 않음.
 *
 * 크로스 필드 검증:
 *  - DB의 `ck_ads_time_window` CHECK가 이미 `endTime <= startTime`을
 *    거부하지만, 제약 위반 예외가 벤더 특화 메시지를 가진
 *    `DataIntegrityViolation`으로 버블링됨. 여기서 규칙을 명시적으로
 *    주장(assert)하여 API가 필드 수준 규칙에 대한 Bean Validation의 모양과
 *    일치하는 깔끔한 field-error 맵
 *    (`{endTime: "must be after startTime"}`)을 반환하게 함. DB 제약은 직접
 *    쓰기(seed 스크립트, 수동 SQL)에 대한 최후의 가드로 유지됨.
 */
@Service
class AdService(
    private val adRepository: AdRepository,
    /**
     * Sub-AC 50201.1 — 스케줄 변경은 SSE 브리지가 영향받을 수 있는 모든
     * 플레이어로 `PLAYLIST_UPDATE` 이벤트를 팬아웃할 수 있도록 스프링
     * 애플리케이션 이벤트 버스에서 자신을 알림.
     *
     * 이벤트는 [me.owldev.adsignage.sse.PlaylistUpdatedSseListener]가 소비하며,
     * 이는 `@TransactionalEventListener(AFTER_COMMIT)`로 연결되어 있어
     * 브로드캐스트가 [updateSchedule]의 `@Transactional` 경계가 커밋된 **이후에만**
     * 실행됨 — 이벤트를 받는 플레이어의 재조회가 새 스케줄을 보도록 보장.
     *
     * publisher를 [ApplicationEventPublisher] 인터페이스 뒤에 두면
     * (SSE registry를 직접 호출하지 않고) AdService의 SSE 모듈에 대한
     * 기존 의존성 없는 자세를 유지함 — 단위 테스트는 web/SSE 레이어를
     * 끌어들이지 않고 `AdService(adRepository = mock, eventPublisher = noop)`을
     * 부팅함.
     */
    private val eventPublisher: ApplicationEventPublisher,
    /**
     * 광고가 삭제되면 그 광고를 담고 있던 모든 디바이스 큐 행도 함께 정리해
     * dangling reference 가 남지 않도록 한다. PlaylistController 가 큐에 있는
     * 광고 id 만으로 ads 를 가져오므로 큐 행이 남아 있어도 곧바로 사라지긴
     * 하지만, 어드민 큐 화면에서 "삭제된 광고" 가 보이는 것을 막기 위해
     * 명시적으로 지운다.
     */
    private val queueRepository: DeviceAdQueueRepository,
) {

    private val log = LoggerFactory.getLogger(AdService::class.java)

    /**
     * id가 [adId]이고 소유자가 [advertiserId]인 광고의 스케줄 필드를 교체.
     * 영속화된 [Ad](업데이트된 스케줄 포함)를 반환. 행이 절반만 적용된
     * 스케줄로 관찰 가능하게 남지 않도록 단일 트랜잭션에서 실행됨.
     *
     * @throws AdNotFoundException     `(id, advertiserId)`에 일치하는 행이 없을 때
     * @throws InvalidScheduleException `endTime <= startTime`일 때
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
        val ad = adRepository.findByIdAndAdvertiserId(adId, advertiserId)
            .orElseThrow {
                log.info(
                    "updateSchedule: ad not found or not owned (adId={}, advertiserId={})",
                    adId, advertiserId,
                )
                AdNotFoundException(adId)
            }

        // 도메인 룰은 entity 가 강제. validation 위반 시 InvalidScheduleException
        // 이 도메인 layer 에서 직접 던져진다.
        ad.changeSchedule(startTime, endTime, dailyPlayCount)
        if (campaignStartDate != null && campaignEndDate != null) {
            ad.changeCampaign(campaignStartDate, campaignEndDate)
        }

        // Hibernate는 @Transactional 경계 내에서 dirty-checking을 수행하지만,
        // 반환된 참조가 영속성 컨텍스트가 커밋한 것이도록(테스트가 플러시와
        // 경합하지 않고 다시 읽을 수 있도록) save()를 명시적으로 호출.
        val saved = adRepository.save(ad)
        log.info(
            "updateSchedule: adId={} advertiserId={} startTime={} endTime={} dailyPlayCount={}",
            saved.id, saved.advertiserId, saved.startTime, saved.endTime, saved.dailyPlayCount,
        )
        // Sub-AC 50201.1 — 애플리케이션 이벤트 버스에서 플레이리스트 변경을
        // 알림. 매칭 리스너는 AFTER_COMMIT에 바인딩되어 있어 SSE 브로드캐스트는
        // 이 `@Transactional` 메서드가 커밋된 이후에만 실행됨. publish 호출
        // 자체는 인-프로세스이고 동기적이지만, 향후 기능이 등록할 수 있는
        // 예상치 못한 pre-commit 리스너가 호출자가 이미 성공한 스케줄 쓰기를
        // 절대 롤백할 수 없도록 안전 장치(belt-and-braces) 가드로 try/catch로
        // 감쌈. AFTER_COMMIT 리스너는 이 호출을 실패시킬 수 없음.
        publishPlaylistUpdated(saved)
        return saved
    }

    /**
     * id가 [adId]이고 소유자가 [advertiserId]인 광고를 가져오거나, 두 조건
     * 중 하나라도 실패하면 [AdNotFoundException]을 던짐. 추후 단일 광고
     * 읽기 엔드포인트를 위해 노출됨(테스트에서도 사용).
     */
    @Transactional(readOnly = true)
    fun findOwned(adId: String, advertiserId: String): Ad =
        adRepository.findByIdAndAdvertiserId(adId, advertiserId)
            .orElseThrow { AdNotFoundException(adId) }

    /** 호출 광고주가 소유한 모든 광고를 최신 순으로 반환. */
    @Transactional(readOnly = true)
    fun listOwned(advertiserId: String): List<Ad> =
        adRepository.findAllByAdvertiserIdOrderByCreatedAtDesc(advertiserId)

    /**
     * 새 광고 생성. 호출자는 이미 업로드된 영상의 [videoFilename] 을 들고
     * 와서 제목과 스케줄과 함께 묶는다.
     *
     * 한 트랜잭션에서 영속화하고, 커밋 후 PLAYLIST_UPDATE SSE 이벤트를
     * 발행해 디바이스가 새 플레이리스트를 즉시 반영하게 한다.
     *
     * @throws InvalidScheduleException `endTime <= startTime`일 때
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
        val saved = adRepository.save(
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
     * 디바이스는 SSE 로 알림을 받고 즉시 새 플레이리스트를 가져와 사라진
     * 광고를 송출에서 제외한다.
     */
    @Transactional
    fun delete(adId: String, advertiserId: String) {
        val ad = adRepository.findByIdAndAdvertiserId(adId, advertiserId)
            .orElseThrow { AdNotFoundException(adId) }
        // 큐 행을 먼저 정리해야 ads(parent) 삭제 후 dangling 큐 행이 남지 않는다.
        val removedQueueRows = queueRepository.deleteAllByAdId(adId)
        adRepository.delete(ad)
        log.info(
            "delete: adId={} advertiserId={} removed_queue_rows={}",
            adId, advertiserId, removedQueueRows,
        )
        publishPlaylistUpdated(ad)
    }

    // -------------------------------------------------------------------------
    // 내부 구현
    // -------------------------------------------------------------------------

    /**
     * [me.owldev.adsignage.sse.PlaylistUpdatedSseListener]가 소비하는
     * [PlaylistUpdatedEvent]를 발행. SSE 리스너는
     * `TransactionPhase.AFTER_COMMIT`에 바인딩되어 있어 브로드캐스트가 이
     * 서비스의 둘러싼 트랜잭션이 커밋될 때까지 실행되지 않음.
     *
     * publish 호출은 잘못 동작하는 이벤트 리스너가 스케줄 쓰기를 절대
     * 롤백하지 않도록 try/catch로 감쌈 — 호출자의 응답(영속화된 [Ad]와
     * 함께 200)은 SSE 전송 성공 여부와 무관하게 올바르므로 여기서의
     * 실패는 로깅하고 삼킴. 푸시를 놓친 플레이어는 다음 주기적 폴링/
     * 재연결 시 새 스케줄을 받음.
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
