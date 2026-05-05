package me.owldev.adsignage.domain.playevent

import me.owldev.adsignage.bounded.context.device.application.port.out.database.DeviceRepositoryPort
import me.owldev.adsignage.domain.playevent.dto.CreatePlayEventRequest
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 플레이어의 "광고 시작 / 광고 종료" 텔레메트리 채널
 * (`POST /api/devices/{deviceId}/play-events`)을 위해 [PlayEvent]를 기록하고,
 * 호출자가 권위적인 서버 측 증가를 관찰할 수 있도록 쓰기 후 일일 카운트를
 * 노출하는 서비스.
 *
 * 두 가지 책임이며, 모두 동일한 정규 day-window에 뿌리를 둠:
 *
 *  1. **영속화** 신호당 정확히 한 행(중복 제거 없음, 레이트 리미팅 없음).
 *     서버는 권위적 *로그*; 플레이어는 지연 민감한 차단을 위한 권위적
 *     *cap 강제자*.
 *  2. **보고** 일관된 타임존에서 "오늘"에 대한 광고의 캠페인 전반
 *     FINISHED 카운트. Sub-AC 20203은 이를 "추후 읽기 경로"에서 연결된
 *     엔드포인트로 승격함 — POST 응답과 대시보드 GET
 *     (`/api/ads/{adId}/play-events/daily-count`) 둘 다 동일한 private
 *     헬퍼를 통해 카운트를 계산하므로 클라이언트가 `POST` 후에 관찰하는
 *     증가가 대시보드가 나중에 읽는 것과 정확히 일치함.
 *
 * **왜 UTC가 아닌 단일 타임존(운영자 로컬)인가.** `web/lib/dailyCount.ts`가
 * 강제하는 cap은 *디바이스 로컬 자정*에 롤오버하며, 운영자의 벽시계 일
 * (로컬 시간의 "HH:mm"인 `startTime`/`endTime`)과 일치. 서버는 동일한
 * 달력 피봇을 사용해야 함. 그렇지 않으면 23:30 KST에 기록된 재생이 서버
 * 관점에서는 어제의 카운트, 플레이어 관점에서는 내일의 카운트로 들어감.
 * 타임존은 `adsignage.daily-count.zone-id`로 구성 가능하며 stream.owl-dev.me의
 * 데모 배포에 맞추기 위해 기본값은 `Asia/Seoul`.
 *
 * **왜 FINISHED만 카운트하는가.** 일일 cap은 완료된 재생을 위한 것 —
 * [PlayEventType] 독스트링 참조. STARTED 행도 여전히 영속화됨(보고가
 * 시청 완료율을 표면화할 수 있도록)이지만 절대 cap 집계에 들어가지 않음.
 * 이 코드베이스에서 "daily count"의 모든 소비자가 이미 암묵적으로 FINISHED를
 * 의미하므로 DTO 필드는 `serverDailyFinishedCount`가 아닌 `serverDailyCount`로
 * 명명됨.
 *
 * 컨트롤러에서 직접 영속화하지 않고 얇은 서비스를 두는 이유:
 *  - [PlayEventRepository]를 컨트롤러 표면에서 제외하여 추후 소유권/광고주
 *    격리 패스가 한 메서드에서 도착할 수 있게 함.
 *  - `occurredAt` 폴백(플레이어 제공, 없으면 서버 시간)은 와이어 관심사가
 *    아닌 도메인 규칙이므로 컨트롤러가 아닌 여기에 속함.
 *  - 코드베이스 내 다른 모든 도메인의 구조를 미러링
 *    ([me.owldev.adsignage.domain.ad.AdService],
 *    [me.owldev.adsignage.domain.assignment.DeviceAssignmentService]).
 */
@Service
class PlayEventService(
    private val playEventRepository: PlayEventRepository,
    /**
     * 디바이스가 광고를 시작/종료할 때 device.lastSeenAt 을 갱신해 어드민
     * 모니터링이 "이 디바이스가 살아있다" 신호로 사용할 수 있게 한다.
     * play-event 자체가 가장 자연스러운 heartbeat 라(15-30초마다 발생) 별도
     * heartbeat 엔드포인트를 두지 않고 이 경로에서 함께 갱신.
     */
    private val deviceRepository: DeviceRepositoryPort,
    @Value("\${adsignage.daily-count.zone-id:Asia/Seoul}")
    private val zoneIdProperty: String,
) {

    private val log = LoggerFactory.getLogger(PlayEventService::class.java)

    /**
     * 달력-일 피봇이 사용하는 타임존. 생성 시 한 번 해석됨(잘못된 값은
     * 시작 시 `ZoneRulesException`으로 크래시되며, 이것이 올바른 실패 모드 —
     * 잘못 설정된 피봇은 그렇지 않으면 조용히 잘못 계산함).
     */
    private val zoneId: ZoneId = ZoneId.of(zoneIdProperty)

    /**
     * [request]에서 파생된 [deviceId]에 대한 단일 [PlayEvent]를 영속화하고,
     * 영속화된 행과 함께 증가 후 서버 일일 카운트(설정된 타임존의 광고
     * 달력일에 대한 FINISHED 이벤트)를 반환.
     *
     * 사전 조건:
     *  - [deviceId]가 비어 있지 않음(컨트롤러 경로 변수; 프레임워크 가드).
     *  - [request]가 Bean Validation을 통과 — `adId`가 비어 있지 않고
     *    `eventType`이 유효한 [PlayEventType].
     *
     * 동작:
     *  - 플레이어가 `occurredAt`을 생략하면 기본값 "now". 누락된 값을 거부하지
     *    않는 이유는 단순한 클라이언트(데모 데스크의 curl)가 엔드포인트를
     *    핑하기 위해 ISO-8601을 손으로 작성할 필요가 없도록 하기 위함.
     *  - 플레이어가 `occurredAt`을 제공해도 `receivedAt`은 항상 엔터티
     *    기본값을 통해 서버 측에서 스탬핑되므로, 디바이스 시계 편차에
     *    무관하게 네트워크 도착 순서로 로그를 정렬할 수 있음.
     *  - 저장은 쓰기 트랜잭션 내부에서 실행되어 향후 @Async 리스너(예:
     *    analytics 토픽으로 팬아웃)가 [AdService]와 일관된 AFTER_COMMIT
     *    시맨틱을 훅으로 걸 수 있게 함. 같은 트랜잭션 내에서 카운트를 읽으면
     *    방금 삽입한 행을 볼 수 있으므로 응답은 항상 증가 후 값을 반영함.
     *  - 카운트는 `occurredAt`(플레이어가 이벤트가 발생했다고 믿는 벽시계)에
     *    대해 계산되므로 플레이어가 제공한 23:59:30 타임스탬프로 보고된
     *    STARTED는 1분 뒤에 보고된 해당 FINISHED와 같은 날에 카운트됨 —
     *    FINISHED의 서버 수신이 전송 중에 자정을 넘더라도.
     */
    @Transactional
    fun record(deviceId: String, request: CreatePlayEventRequest): RecordedPlayEvent {
        // Bean Validation이 이미 이것들이 non-null임을 증명했음; !!는 Kotlin의
        // nullable 모양을 엔터티의 불변식으로 축약함.
        val adId = request.adId!!
        val eventType = request.eventType!!
        val occurredAt = request.occurredAt ?: Instant.now()

        val saved = playEventRepository.save(
            PlayEvent(
                deviceId = deviceId,
                adId = adId,
                eventType = eventType,
                occurredAt = occurredAt,
            ),
        )
        // 디바이스 활동 신호 갱신. 디바이스가 register 후 다시 호출하지 않아도
        // 광고 송출이 실제로 일어나는 동안에는 이 경로로 lastSeenAt 이 계속
        // 갱신된다. 어드민 /api/devices 의 online 판정이 이 값을 fallback 으로
        // 사용한다(SSE 연결이 끊어졌어도 최근 play-event 가 있으면 살아있는 것).
        // device 행이 사라졌어도(아직 register 안 했거나 삭제됨) 텔레메트리
        // 자체는 그대로 기록 — orElse(null) 후 ?.also 로 best-effort.
        deviceRepository.findById(deviceId)?.also {
            it.touch()
            deviceRepository.save(it)
        }

        // 같은 트랜잭션 내에서 증가 후 카운트를 읽어 방금 쓴 행을 관찰함.
        // 어떤 이벤트 타입이 기록되었든 무관하게 항상 FINISHED — cap 시맨틱 —
        // 를 보고함.
        val serverDailyCount = dailyFinishedCount(adId = adId, at = occurredAt)

        // 데모 친화적 로깅 — 운영자는 라이브 실행 중 이 라인을 tail하여
        // 재생 이벤트가 서버에 도달하는지 확인함.
        log.info(
            "play-event recorded: id={} deviceId={} adId={} eventType={} occurredAt={} serverDailyCount={}",
            saved.id, saved.deviceId, saved.adId, saved.eventType, saved.occurredAt, serverDailyCount,
        )
        return RecordedPlayEvent(event = saved, serverDailyCount = serverDailyCount)
    }

    /**
     * [at]을 포함하는 달력일(설정된 타임존)에 대한 [adId]의 서버 측 일일
     * FINISHED 카운트. 읽기 전용; 어느 트랜잭션 컨텍스트에서 호출해도 안전.
     * 권위적인 cap이 필요한 모든 읽기 경로에서 이를 사용 — 클라이언트가
     * [record]를 통해 관찰하는 증가는 동일한 윈도우 수학을 사용함.
     *
     * @param adId 재생을 카운트할 광고. FK 없음; 알려지지 않은 id는 404가
     *             아닌 0을 반환하여 대시보드가 새로 생성된 광고에 대해
     *             "오늘 0회 재생"을 표시할 수 있음.
     * @param at   달력일이 윈도우를 정의하는 Instant. 기본값은 "now" —
     *             서버 부기와 플레이어가 이벤트 발생 시점에 대해 믿는 것이
     *             일치하도록 [record]에서 플레이어가 제공한 `occurredAt`을
     *             전달.
     */
    @Transactional(readOnly = true)
    fun dailyFinishedCount(adId: String, at: Instant = Instant.now()): Long {
        val (from, to) = dailyWindow(at)
        return playEventRepository.countByAdIdAndEventTypeAndOccurredAtBetween(
            adId = adId,
            eventType = PlayEventType.FINISHED,
            from = from,
            to = to,
        )
    }

    /**
     * 설정된 타임존의 반-개방 `[startOfDay, startOfNextDay)` 윈도우를 JPA
     * 쿼리를 위해 UTC instant로 렌더링. 피봇을 여기에 중앙화하는 것이 이
     * 서비스가 그 책임을 갖는 모든 이유 — `record`와 모든 향후 읽기
     * 엔드포인트는 윈도우에 동의해야 함. 그렇지 않으면 클라이언트가
     * "POST returned 47" 다음에 "GET returned 46"을 보게 됨.
     *
     * 왜 반-개방인가: `web/lib/playlist.ts: isAdActive`(시작 포함, 끝 제외)와
     * 일치하며 자정 경계에서의 off-by-one 모호성을 회피 — 정확히 하루가
     * 00:00:00.000을 소유.
     */
    private fun dailyWindow(at: Instant): Pair<Instant, Instant> {
        val day: LocalDate = at.atZone(zoneId).toLocalDate()
        val from = day.atStartOfDay(zoneId).toInstant()
        val to = day.plusDays(1).atStartOfDay(zoneId).toInstant()
        return from to to
    }
}

/**
 * [PlayEventService.record]가 반환하는 튜플: 영속화된 행과 광고에 대한
 * 증가 후 서버 측 일일 FINISHED 카운트. 컨트롤러 호출 사이트가 무엇을
 * 의미하든 될 수 있는 구조적 쌍이 아니라 도메인 개념("현재 카운트와 함께
 * 기록된 이벤트")으로 읽히도록 (`Pair`가 아닌) 별개의 타입.
 *
 * `serverDailyCount`는 이벤트의 `occurredAt`이 포함된 달력일에 대한 광고의
 * FINISHED 카운트이며, 설정된 타임존에서 계산됨. 타임존 근거는
 * [PlayEventService] 독스트링 참조.
 */
data class RecordedPlayEvent(
    val event: PlayEvent,
    val serverDailyCount: Long,
)
