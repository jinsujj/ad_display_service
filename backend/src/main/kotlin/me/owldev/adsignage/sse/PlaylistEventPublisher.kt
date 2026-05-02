package me.owldev.adsignage.sse

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

/**
 * Sub-AC 50003.3 — "playlist-updated" SSE 이벤트의 와이어 측 publisher.
 *
 * # 책임
 * [SseEmitterRegistry]에서 `deviceId`에 대한 모든 라이브 emitter를 가져와서
 * 단일 SSE 이벤트를 브로드캐스트하므로, 해당 디바이스에 연결된 모든 플레이어
 * 페이지가 새 플레이리스트를 재조회(또는 인라인으로 적용)하게 됨.
 *
 * # 왜 (registry.broadcast를 인라인하지 않고) 전용 publisher인가
 *  - 도메인 코드(예: `AdService.create`, `ScheduleService.update`)가 SSE
 *    배관에서 자유로워짐 — 타입화된 메서드 하나만 호출.
 *  - `name(...) + .data(...)` 이벤트 빌더 모양을 중앙화하여 `PLAYLIST_UPDATE`의
 *    와이어 계약이 정확히 한 곳에 존재하게 함.
 *  - 추후 AC가 호출 사이트를 건드리지 않고도 횡단 관심사(레이트 리미팅,
 *    감사 로그, 메트릭)를 추가할 수 있게 함.
 *
 * # 이벤트 이름 선택
 * AC 텍스트는 이를 "playlist-updated" SSE 이벤트라고 부름. [SseEventNames]의
 * 기존 와이어 상수는 `PLAYLIST_UPDATE`이고, Next.js 플레이어
 * (`web/hooks/usePlayerSse.ts`)는 `addEventListener("PLAYLIST_UPDATE", …)`로
 * 수신함. publisher와 플레이어가 단일 와이어 이름에 동의하도록 여기서
 * [SseEventNames.PLAYLIST_UPDATE]를 사용 — "playlist-updated"는 *개념적*
 * 이벤트 클래스이고, `PLAYLIST_UPDATE`는 SSE의 `event:` 라인이 운반하는
 * 리터럴 값. 와이어 이름을 변경하면 프런트엔드 롤을 조율해야 하므로
 * 이 sub-AC 범위 밖.
 *
 * # 실패 처리
 * emitter별 `IOException`과 `IllegalStateException`은
 * [SseEmitterRegistry.broadcast]에서 흡수됨: 실패한 emitter는 registry에서
 * 제거되고, 살아남은 emitter에 대해서는 브로드캐스트가 여전히 완료되며,
 * 호출자는 예외를 보지 않음. 따라서 publisher는 다운스트림 네트워크 사유로
 * throw하지 않음 — 유일한 실패 모드는 프로그래머 오류(빈 deviceId)이며
 * 이는 입력 검증에서 fail-fast 처리됨.
 */
@Service
class PlaylistEventPublisher(
    private val registry: SseEmitterRegistry,
) {

    private val log = LoggerFactory.getLogger(PlaylistEventPublisher::class.java)

    /**
     * AC 계약과 일치하는 강타입 진입점:
     * `publishPlaylistUpdated(deviceId, payload)`.
     *
     * [payload]를 JSON으로 직렬화한 [SseEventNames.PLAYLIST_UPDATE]라는 SSE
     * 이벤트를 빌드한 뒤, [deviceId] 아래 현재 등록된 모든 emitter로
     * 브로드캐스트.
     *
     * @param deviceId 대상 디바이스 — 비어 있으면 안 됨.
     * @param payload  프런트엔드 `PlaylistUpdatePayload`를 미러링하는 타입화된 페이로드.
     * @return 이벤트를 성공적으로 받은 emitter 수.
     */
    fun publishPlaylistUpdated(deviceId: String, payload: PlaylistUpdatedPayload): Int {
        require(deviceId.isNotBlank()) { "deviceId must not be blank" }
        return broadcast(deviceId, payload)
    }

    /**
     * 이미 직렬화 가능한 페이로드를 보유한 호출자(예: 이 모듈에 의존하지
     * 않는 도메인 서비스에서 조립된 `Map<String, Any?>`)를 위한 느슨한 타입
     * 오버로드. [payload]를 불투명한 JSON으로 취급함 — 스프링의
     * `MappingJackson2HttpMessageConverter`가 기본 `ObjectMapper`로 직렬화함.
     *
     * @param deviceId 대상 디바이스 — 비어 있으면 안 됨.
     * @param payload  임의의 JSON 직렬화 가능한 객체.
     * @return 이벤트를 성공적으로 받은 emitter 수.
     */
    fun publishPlaylistUpdated(deviceId: String, payload: Any): Int {
        require(deviceId.isNotBlank()) { "deviceId must not be blank" }
        return broadcast(deviceId, payload)
    }

    private fun broadcast(deviceId: String, payload: Any): Int {
        // 청자가 없는 디바이스에 대해 빌드를 단축하기 위해 라이브 emitter를
        // 먼저 스냅샷(아무도 연결되지 않았을 때 JSON 직렬화 절약 — 라운드
        // 로빈 데모에서 플레이어가 열려 있지 않은 동안 플레이리스트 변경이
        // 발생할 수 있어 관련됨). registry는 내부적으로도 팬아웃하므로 이
        // 스냅샷은 순수 조기 종료(early-exit)용.
        val live = registry.getByDeviceId(deviceId)
        if (live.isEmpty()) {
            log.debug("PLAYLIST_UPDATE skipped (no live emitters): device={}", deviceId)
            return 0
        }

        val event = SseEmitter.event()
            .name(SseEventNames.PLAYLIST_UPDATE)
            .data(payload)

        val delivered = registry.broadcast(deviceId, event)
        log.info(
            "PLAYLIST_UPDATE → device={} emitters={} delivered={}",
            deviceId,
            live.size,
            delivered,
        )
        return delivered
    }
}
