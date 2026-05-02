package me.owldev.adsignage.sse

import me.owldev.adsignage.domain.assignment.DeviceAssignmentRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

/**
 * [PlaylistUpdatedEvent]를 SSE 와이어로 브리지하여, 변경의 영향을 받을 수
 * 있는 디바이스에 현재 연결된 모든 emitter로
 * [SseEventNames.PLAYLIST_UPDATE] 이벤트를 브로드캐스트.
 *
 * "광고주가 광고 스케줄을 변경 → 모든 활성 플레이어가 재조회" 루프를
 * 동작시키는 publisher/listener 쌍의 listener 절반(device-remap 경로의
 * 형제 격; 데모 시나리오 #1 + #2):
 *
 *  1. 광고주가 `PUT/PATCH /api/ads/{id}/schedule` 호출.
 *  2. [me.owldev.adsignage.domain.ad.AdService.updateSchedule]가 새 스케줄을
 *     쓰고 `@Transactional` 메서드 내부에서 [PlaylistUpdatedEvent]를 발행.
 *  3. 트랜잭션이 커밋됨 — 행이 영속화된 **이후에만** 스프링이 이 리스너를
 *     호출(이 리스너는 [TransactionalEventListener]의 phase
 *     [TransactionPhase.AFTER_COMMIT]로 연결됨).
 *  4. 이 리스너는 [DeviceAssignmentRepository.findAllByActiveTrue]를 통해
 *     영향받는 device_id 집합을 해석하고, [PlaylistEventPublisher]에 각
 *     디바이스로 `PLAYLIST_UPDATE` 이벤트를 푸시하도록 요청.
 *  5. `/player/{deviceId}`의 Next.js 플레이어는 장기 SSE 구독을 유지하다가
 *     이벤트를 받고 플레이리스트를 재조회.
 *
 * # 영향받는 디바이스 해석
 * 현재 데이터 모델에서 [me.owldev.adsignage.domain.ad.Ad]는 음식점 전반에
 * 걸쳐 전역적임 — 모든 디바이스의 플레이리스트는 잠재적으로 모든 광고를
 * 포함. 따라서 영향받는 디바이스 집합은 "현재 활성 할당이 있는 모든
 * 디바이스". 어디에도 프로비저닝되지 않은 디바이스로 푸시하지 않도록
 * 활성-할당 집합을 "구독된 디바이스"의 약한 프록시(soft proxy)로 사용;
 * 이는 부팅 시 브로드캐스트를 조용하게 유지하고 2개 디바이스를 2개
 * 음식점에 연결하는 데모와도 부합함.
 *
 * registry가 활성-할당 테이블에 없는 디바이스에 대해 emitter를 보유하고
 * 있는 경우(예: 관리자가 매핑하기 전에 갓 생성된 deviceId로 열린 플레이어
 * 페이지), 그 디바이스는 단순히 이 브로드캐스트를 받지 못함. 이는 허용됨:
 * 매핑되지 않은 플레이어는 새로고침할 플레이리스트가 없고, 매핑되면 형제
 * 경로의 [SseEventNames.MAPPING_CHANGED] 이벤트를 보고 새 스케줄이 이미
 * 반영된 신선한 플레이리스트를 가져옴.
 *
 * # 왜 동기 `@EventListener`가 아니라 AFTER_COMMIT인가
 *  - **DB와의 일관성**: AdService 트랜잭션이 롤백되면(예: 플러시 시점에
 *    크로스 필드 검증이 발생) PLAYLIST_UPDATE 이벤트가 전송되지 않으므로,
 *    플레이어가 영속화된 상태와 일치하지 않는 가짜 새로고침을 보지 않음.
 *  - **재조회 순서**: 플레이어가 PLAYLIST_UPDATE를 받고 즉시 플레이리스트를
 *    가져갈 때, 스케줄 행이 이미 커밋되어 읽기에서 보임.
 *  - **실패 격리**: emitter별 SSE 전송 실패는 리스너가 post-commit에서
 *    동작하므로 스케줄 쓰기를 롤백시킬 수 없음. 리스너 자체 내에서는
 *    [PlaylistEventPublisher]가 실패 처리를 [SseEmitterRegistry.broadcast]에
 *    위임하여 정상 형제(emitter)를 오염시키지 않고 호출별로 불량 emitter를
 *    제거함.
 *
 * # `fallbackExecution = true`
 * 이벤트가 트랜잭션 *밖*에서 발행되는 경우(예: `@Transactional` 없이
 * 서비스를 호출하는 단위 테스트, 또는 트랜잭션 서비스를 우회하는 향후 임시
 * 관리 도구), 스프링은 바인딩할 트랜잭션 phase가 없기 때문에 보통 이벤트를
 * 조용히 드롭함. `fallbackExecution = true`로 설정하면 스프링이 이런
 * 경우에도 즉시 리스너를 호출하므로 브로드캐스트가 여전히 발생함 —
 * [DeviceMappingChangedSseListener]와 동일한 선택.
 *
 * # 왜 registry의 전체 keyset으로 푸시하지 않는가
 * [SseEmitterRegistry]는 연결된 모든 emitter를 알지만, 의도적으로 직접
 * 순회하지 않음. 활성-할당 테이블이 "이 디바이스가 프로비저닝됨"의 단일
 * 진실 출처(source of truth)임; registry를 순회하면 끊어진/방치된 탭으로
 * 푸시하게 되고 이 리스너를 registry의 내부 자료 구조에 결합시킴.
 * publisher를 거치면(`registry.broadcast(deviceId, event)`를 호출함)
 * registry의 keyset이 캡슐화된 상태로 유지되며, 이 코드를 건드리지 않고도
 * 사용되지 않는 디바이스별 리스트를 적극적으로 삭제할 수 있음.
 */
@Component
class PlaylistUpdatedSseListener(
    private val publisher: PlaylistEventPublisher,
    private val assignmentRepository: DeviceAssignmentRepository,
) {

    private val log = LoggerFactory.getLogger(PlaylistUpdatedSseListener::class.java)

    @TransactionalEventListener(
        phase = TransactionPhase.AFTER_COMMIT,
        fallbackExecution = true,
    )
    fun onPlaylistUpdated(event: PlaylistUpdatedEvent) {
        val activeAssignments = try {
            assignmentRepository.findAllByActiveTrue()
        } catch (ex: Exception) {
            // 활성-할당 집합 읽기는 절대 리스너를 다운시키면 안 됨 —
            // 로그를 남기고 브로드캐스트를 건너뛰어 호출자에게는 스케줄
            // 쓰기가 여전히 성공한 것으로 보이도록 함. 플레이어는 다음
            // 주기적 새로고침/재연결 시점에 변경을 받음.
            log.warn(
                "PLAYLIST_UPDATE listener: failed to load active assignments for adId={}: {}",
                event.adId, ex.message,
            )
            return
        }

        if (activeAssignments.isEmpty()) {
            log.debug(
                "PLAYLIST_UPDATE listener: no active device assignments — skipping broadcast (adId={})",
                event.adId,
            )
            return
        }

        var totalDelivered = 0
        var devicesNotified = 0
        for (assignment in activeAssignments) {
            val payload = PlaylistUpdatedPayload(
                deviceId = assignment.deviceId,
                restaurantId = assignment.restaurantId,
                updatedAt = event.changedAt,
            )
            try {
                val delivered = publisher.publishPlaylistUpdated(assignment.deviceId, payload)
                if (delivered > 0) {
                    devicesNotified++
                    totalDelivered += delivered
                }
            } catch (ex: Exception) {
                // 디바이스 하나가 잘못되었다고 팬아웃이 멈춰서는 안 됨.
                // publisher는 이미 registry의 broadcast()를 통해 emitter별
                // IO/상태 오류를 흡수함; 이 catch는 프로그래머 오류
                // (예: 데이터 버그가 될 수 있는 빈 deviceId)를 커버.
                log.warn(
                    "PLAYLIST_UPDATE fan-out failed for device={}: {}",
                    assignment.deviceId, ex.message,
                )
            }
        }
        log.info(
            "PLAYLIST_UPDATE fan-out adId={} advertiserId={} activeDevices={} devicesNotified={} totalEmittersDelivered={}",
            event.adId,
            event.advertiserId,
            activeAssignments.size,
            devicesNotified,
            totalDelivered,
        )
    }
}
