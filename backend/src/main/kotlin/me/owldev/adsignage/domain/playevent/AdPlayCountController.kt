package me.owldev.adsignage.domain.playevent

import me.owldev.adsignage.domain.playevent.dto.DailyPlayCountResponse
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.time.ZoneId

/**
 * AC 20203 Sub-AC 3 — 광고에 대한 서버의 권위적인 일일 FINISHED 카운트를
 * 표면화하는 읽기 측 엔드포인트.
 *
 * 와이어 계약:
 *
 *   GET /api/ads/{adId}/play-events/daily-count
 *
 *   ⇒ 200 OK
 *      {
 *        "adId":   "<uuid>",
 *        "date":   "YYYY-MM-DD",
 *        "zoneId": "Asia/Seoul",
 *        "count":  42
 *      }
 *
 * **왜 `/api/ads/{adId}/...` 아래에 마운트되는가** (POST 대응을 호스팅하는
 * 디바이스 경로 아래가 아닌):
 *
 *  - 일일 cap은 **캠페인 전반**, 디바이스별이 아님. 광고주는 "200회 재생/일"
 *    을 구매 — 광고를 실행하는 모든 스크린에 걸쳐. `/api/ads` 아래에
 *    읽기를 마운트하면 리소스 계보가 시맨틱과 일치 — 어느 단일 디바이스의
 *    속성이 아닌 *광고*의 속성.
 *  - 처음에 cap을 설정한 형제 관리자 라우트인 `/api/ads/{id}/schedule`과
 *    정렬. 운영자는 광고를 열고 자신이 설정한 cap을 보고, 카운트는 동일한
 *    계층에서 발사됨.
 *  - 기존 `/api/ads/{ANY}` 보안 규칙
 *    ([me.owldev.adsignage.config.SecurityConfig]의 `.authenticated()`)을
 *    상속하므로 이 엔드포인트는 기본적으로 JWT 게이트됨 — 보안 예외 불필요.
 *    광고 CRUD에 광고주 소유권 필터링을 추가하는 auth-and-isolation 패스가
 *    이 라우트를 무료로 가져갈 것.
 *
 * **왜 (아직) `?date=` 쿼리 파라미터가 없는가**: 플레이어와 대시보드 둘 다
 * "오늘"을 요청 — 현재 UI에는 과거 재생 표면이 없음. UI 소비자 없이 날짜
 * 산술을 추가하는 것은 낭비된 표면; [PlayEventService.dailyFinishedCount]
 * 내부의 헬퍼가 이미 [Instant]를 받으므로 대시보드가 날짜 선택기를
 * 갖게 되면 `?date=YYYY-MM-DD` 파라미터가 한 메서드에 도착할 수 있음.
 *
 * **왜 알려지지 않은 adId가 404가 아닌 `count=0`으로 200을 반환하는가**:
 * 대시보드는 광고 생성 직후 — 어떤 재생도 도착하기 전 — 이 엔드포인트를
 * 폴링하며, 거기서 404를 표면화하면 데이터가 아직 없는 새로 생성된 광고에
 * "로딩 중…" 상태를 강제하게 됨. "그 id에 대한 재생 없음" 시맨틱은
 * `web/lib/dailyCount.ts`가 누락된 키를 처리하는 방식에 맞춰 0 카운트로
 * 인코딩됨.
 */
@RestController
@RequestMapping("/api/ads/{adId}/play-events")
class AdPlayCountController(
    private val playEventService: PlayEventService,
    @Value("\${adsignage.daily-count.zone-id:Asia/Seoul}")
    private val zoneIdProperty: String,
) {

    private val log = LoggerFactory.getLogger(AdPlayCountController::class.java)

    /**
     * 서비스가 내부적으로 사용한 동일한 문자열을 응답이 에코할 수 있도록
     * 생성 시 해석됨 — 잘못 설정된 타임존은 조용히 잘못 계산하지 않고 시작
     * 시 fail-fast.
     */
    private val zoneId: ZoneId = ZoneId.of(zoneIdProperty)

    /**
     * 설정된 타임존의 오늘 달력일에 대한 [adId]의 FINISHED 이벤트 카운트를
     * 반환. 본문에 에코된 `date`와 `zoneId`는 클라이언트가 cap을 잘못
     * 렌더링하기 전에 서버-클라이언트 타임존 드리프트를 감지할 수 있게 함.
     */
    @GetMapping(
        "/daily-count",
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    fun getDailyCount(
        @PathVariable("adId") adId: String,
    ): ResponseEntity<DailyPlayCountResponse> {
        val now = Instant.now()
        val count = playEventService.dailyFinishedCount(adId = adId, at = now)
        val date = now.atZone(zoneId).toLocalDate().toString() // ISO YYYY-MM-DD 형식
        log.info(
            "GET /api/ads/{}/play-events/daily-count → date={} zone={} count={}",
            adId, date, zoneId.id, count,
        )
        return ResponseEntity.ok(
            DailyPlayCountResponse(
                adId = adId,
                date = date,
                zoneId = zoneId.id,
                count = count,
            ),
        )
    }
}
