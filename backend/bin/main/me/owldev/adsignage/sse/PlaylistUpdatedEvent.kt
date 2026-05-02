package me.owldev.adsignage.sse

import java.time.Instant

/**
 * 플레이리스트/스케줄이 생성되거나 수정되었음을 알리는 애플리케이션 이벤트.
 * 즉, 활성 플레이어가 팬아웃해야 할 광고 및 윈도우 집합이 직전과 더 이상
 * 동일하지 않다는 의미.
 *
 * 광고 도메인의 변경(mutating) 서비스(현재는 [me.owldev.adsignage
 * .domain.ad.AdService.updateSchedule])가 변경을 영속화하는 `@Transactional`
 * 경계 내부에서 발행. [PlaylistUpdatedSseListener]가 이를 소비하여
 * 영향을 받을 수 있는 모든 디바이스로 `PLAYLIST_UPDATE` SSE 메시지를
 * 팬아웃.
 *
 * # 왜 (직접 publisher 호출 대신) 애플리케이션 이벤트인가
 *  - 도메인 서비스가 HTTP / SSE 배관에서 자유로워짐 — `AdService`는
 *    [SseEmitterRegistry] 또는 [PlaylistEventPublisher]에 의존하지 않음.
 *  - [DeviceMappingChangedEvent]가 정립한 패턴을 그대로 따름으로써
 *    "플레이어가 관심 갖는 무언가가 바뀌었음"이 SSE 브리지를 통해
 *    전달되는 모든 흐름이 동일하게 보이고 동일하게 추론되도록 함.
 *  - DB 트랜잭션이 커밋된 **이후에만** 브로드캐스트가 발생하도록 함.
 *    리스너는 `@TransactionalEventListener(phase = AFTER_COMMIT)`로
 *    연결되어 있어:
 *      1. 트랜잭션이 롤백되면 SSE 이벤트가 전송되지 않음 — 플레이어가
 *         영속화된 상태와 모순되는 가짜 플레이리스트 새로고침을 보지 않음.
 *      2. 플레이어가 PLAYLIST_UPDATE 이벤트를 받고 플레이리스트를
 *         재조회할 때, 새로운 스케줄이 이미 커밋되어 읽기에서 보임.
 *
 * # 영향받는 디바이스 집합 시맨틱
 * 현재 데이터 모델에서 [me.owldev.adsignage.domain.ad.Ad]는 광고주 소유이며
 * 단일 음식점에 한정되지 않음 — 모든 활성 플레이어는 회전(rotation)에
 * 모든 광고를 포함. 따라서 "영향받는 디바이스" 집합은 "현재 활성
 * 할당이 있는 모든 디바이스". 리스너는 전송 시점에 이 집합을 해석하므로
 * 발행 측에서 미리 구체화할 필요가 없음.
 *
 * 향후 호환성: 광고가 음식점별 범위를 갖게 되면, 이 이벤트는
 * `restaurantId`(또는 그 목록)를 운반하고 리스너는 해당 음식점에
 * 할당된 디바이스로 전달을 좁힘. 리스너가 유일한 소비자이므로 필드
 * 추가는 하위 호환성을 유지함.
 *
 * @property advertiserId 광고가 변경된 광고주 — 감사/로그 상관관계 용도로
 *   포함; 현재 전송 범위 지정에는 사용되지 않음.
 * @property adId         스케줄이 변경된 광고 행 — 감사/로그 상관관계 및
 *   SSE 페이로드가 이를 에코할 수 있도록 포함.
 * @property changedAt    변경이 커밋된 시점(발행 시각).
 */
data class PlaylistUpdatedEvent(
    val advertiserId: String,
    val adId: String,
    val changedAt: Instant = Instant.now(),
)
