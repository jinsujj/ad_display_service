package me.owldev.adsignage.domain.playevent

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * Next.js 플레이어가 디바이스에서 스케줄을 회전시키면서 발행하는 단일
 * "광고 시작" 또는 "광고 종료" 신호의 개별 레코드.
 *
 * V100 `play_events` Flyway 마이그레이션을 뒷받침함(append-only 이벤트 로그;
 * 삽입 후 절대 변경되지 않음). `web/lib/dailyCount.ts`가 이미 플레이어의
 * `localStorage`에서 디바이스별 cap을 강제하는데도 서버가 자체 카운트를
 * 유지하는 두 가지 이유:
 *
 *  1. 운영자는 일일 cap을 *캠페인 전반* 숫자로 읽음 — 광고주는
 *     "200회 재생/일"을 구매했지 "디바이스당 200회 재생/일"이 아님.
 *     광고를 재생한 모든 디바이스에 걸쳐 광고당 집계하려면 서버 측 카운트가
 *     필요.
 *  2. WebView의 `localStorage`는 휘발성(공장 초기화, 앱 재설치, 비공개
 *     모드 프로필). 서버 측 텔레메트리 없이는 초기화된 디바이스가 조용히
 *     하루를 새로 시작하여 cap을 이중 소비할 수 있음.
 *
 * 저장 모델은 평탄한 이벤트 로그: 신호당 한 행, 두 인덱스
 * (`(ad_id, event_type, occurred_at)` 복합 인덱스는 일일 cap 카운트 쿼리를
 * 정확히 커버; `(device_id, received_at)` 인덱스는 "그 디바이스가 무엇을
 * 했는지" 디버그 쿼리를 처리).
 *
 * 외래 키 없음 — 근거는 마이그레이션 V100 독스트링 참조(디바이스 ID는
 * 자유 형식 UUID, 광고는 삭제될 수 있으며, 텔레메트리는 둘 다 살아남아야 함).
 */
@Entity
@Table(
    name = "play_events",
    indexes = [
        Index(
            name = "idx_play_events_ad_event_time",
            columnList = "ad_id, event_type, occurred_at",
        ),
        Index(
            name = "idx_play_events_device_received",
            columnList = "device_id, received_at",
        ),
    ],
)
class PlayEvent(
    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    val id: String = UUID.randomUUID().toString(),

    /**
     * 이벤트를 발행한 디바이스의 UUID — 플레이어가
     * `/player/{deviceId}`와 `/api/devices/{deviceId}/stream`에서 사용하는
     * 동일한 id.
     */
    @Column(name = "device_id", nullable = false, updatable = false, length = 36)
    val deviceId: String,

    /**
     * 시작/종료된 광고의 UUID. 라이브 광고에 대해서는 `ads.id`와 일치하지만,
     * 광고 삭제가 과거 텔레메트리를 고아로 만들거나 cascade-삭제할 수 없도록
     * 의도적으로 컬럼에 FK를 **걸지 않음**.
     */
    @Column(name = "ad_id", nullable = false, updatable = false, length = 36)
    val adId: String,

    /**
     * 식별자: STARTED 또는 FINISHED. DB CHECK 제약 `ck_play_events_type`이
     * 이 enum을 미러링하므로 손으로 작성한 INSERT가 알려지지 않은 값을
     * 절대 들어가게 할 수 없음.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, updatable = false, length = 16)
    val eventType: PlayEventType,

    /**
     * 플레이어가 이벤트가 발생했다고 믿는 벽시계 타임스탬프.
     * 플레이어 제공; 신뢰하되 검증. 클라이언트가 생략하면 [receivedAt]으로
     * 폴백([PlayEventService.record] 참조).
     */
    @Column(name = "occurred_at", nullable = false, updatable = false)
    val occurredAt: Instant,

    /**
     * 서버가 수신 시 스탬핑한 벽시계 타임스탬프. 서버 제어이므로 잘못
     * 설정된 디바이스 시계가 이벤트 로그 순서를 바꿀 수 없음.
     */
    @Column(name = "received_at", nullable = false, updatable = false)
    val receivedAt: Instant = Instant.now(),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PlayEvent) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}

/**
 * 플레이어가 발행하는 두 가지 재생 이벤트 신호로,
 * `ck_play_events_type` CHECK 제약과 `POST /api/devices/{deviceId}/play-events`의
 * 와이어 계약과 1:1로 미러링됨.
 *
 * - STARTED: 플레이어의 `<video onPlay>` 핸들러가 발사 — WebView가 새
 *   src의 첫 프레임을 디코딩하고 이제 그리는 중.
 * - FINISHED: `<video onEnded>`가 발사 — 완전한 재생 완료.
 *   운영자가 cap이 중단된 시도가 아닌 화면이 실제로 완료한 재생을 반영하기를
 *   기대하므로 일일 cap 회계는 구체적으로 FINISHED 행을 카운트함.
 */
enum class PlayEventType {
    STARTED,
    FINISHED,
}
