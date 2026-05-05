package me.owldev.adsignage.bounded.context.playlist.adapter.out.sse

import java.time.Instant

/**
 * 플레이리스트/스케줄이 생성되거나 수정되었음을 알리는 애플리케이션 이벤트.
 *
 * 광고/큐 변경 서비스가 변경을 영속화하는 `@Transactional` 경계 내부에서
 * 발행. [PlaylistUpdatedSseListener] 가 이를 소비하여 영향받는 디바이스로
 * `PLAYLIST_UPDATE` SSE 메시지를 팬아웃.
 *
 * 왜 (직접 publisher 호출 대신) 애플리케이션 이벤트인가:
 *  - 도메인 서비스가 HTTP / SSE 배관에서 자유로워짐
 *  - DB 트랜잭션이 커밋된 **이후에만** 브로드캐스트 발생 (AFTER_COMMIT)
 *  - 트랜잭션이 롤백되면 SSE 가 전송되지 않으므로 가짜 새로고침 없음
 */
data class PlaylistUpdatedEvent(
    val advertiserId: String,
    val adId: String,
    val changedAt: Instant = Instant.now(),
)
