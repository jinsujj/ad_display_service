package me.owldev.adsignage.sse

import java.time.Instant

/**
 * 플레이어 페이지로 전송되는 SSE 이벤트의 와이어 포맷 상수와 DTO.
 *
 * 온톨로지 개념 매핑:
 *  - sse_event_type → [EventType] 열거형 값
 *  - sse_payload    → 아래의 데이터 클래스(이벤트 타입당 하나)
 *
 * `/player/{deviceId}`의 Next.js 플레이어는 SSE의 `event:` 필드로 매칭하고
 * `data:`를 해당 페이로드 타입의 JSON으로 파싱.
 */
object SseEventNames {
    /** 플레이어가 연결된 직후 한 번 전송 — 클라이언트가 채널이 정상임을
     *  확인하고 서버 측에서 deviceId를 로깅할 수 있게 함. */
    const val CONNECTED = "CONNECTED"

    /** 관리자가 디바이스를 다른 음식점으로 리매핑할 때 전송 —
     *  플레이어가 새 음식점에 대한 새 플레이리스트를 가져오도록 트리거. */
    const val MAPPING_CHANGED = "MAPPING_CHANGED"

    /** 다른 어떤 이유로 디바이스의 플레이리스트가 변경되었을 때 전송
     *  (예: 광고주가 같은 음식점에 새 광고를 추가/스케줄). 형제 sub-AC를
     *  위해 예약; 이벤트 어휘를 한 곳에 모으기 위해 여기에 정의. */
    const val PLAYLIST_UPDATE = "PLAYLIST_UPDATE"
}

/**
 * [SseEventNames.CONNECTED]의 페이로드.
 *
 * 가벼운 핸드셰이크 — 플레이어는 이를 사용해 SSE 파이프가 살아있음을
 * 확인한 뒤 폴링으로 폴백할지 여부를 결정.
 */
data class ConnectedPayload(
    val deviceId: String,
    val serverTime: Instant = Instant.now(),
)

/**
 * [SseEventNames.MAPPING_CHANGED]의 페이로드.
 *
 * 리매핑 후 상태를 운반하여 플레이어가 별도 GET을 단축할 수 있게 함 —
 * 새 restaurantId가 이미 손에 있음. 플레이어는 여전히 새 음식점의
 * 플레이리스트를 가져와야 하지만, 이 페이로드 덕분에 플레이리스트 왕복을
 * 기다리지 않고 "{restaurant}로 전환 중…" 스플래시를 즉시 렌더링할 수 있음.
 *
 * 온톨로지 매핑:
 *  - deviceId        → device_id
 *  - restaurantId    → device_restaurant_id (리매핑 이후)
 *  - assignmentId    → (이 활성 행에 대한 감사 핸들)
 *  - assignedAt      → 새 매핑이 발효된 시각
 */
data class MappingChangedPayload(
    val deviceId: String,
    val restaurantId: String,
    val assignmentId: String,
    val assignedAt: Instant,
)

/**
 * [SseEventNames.PLAYLIST_UPDATE]의 페이로드 (sub-AC 50003.3).
 *
 * 음식점 리매핑이 아닌 다른 어떤 이유(예: 광고주가 새 광고 추가, 스케줄
 * 편집, 이 디바이스의 음식점에 이미 있는 광고 삭제)로 디바이스의
 * 플레이리스트 내용이 변경되었을 때 [PlaylistEventPublisher.publishPlaylistUpdated]가
 * 전송.
 *
 * `web/hooks/usePlayerSse.ts`에 선언된 TypeScript 와이어 계약
 * `PlaylistUpdatePayload`를 미러링하므로, 플레이어는 클라이언트 롤(roll)을
 * 조율하지 않고도 이 페이로드를 디코딩할 수 있음. 프런트엔드의 관대한
 * 파서는 알 수 없는 추가 키와 누락된 선택 필드를 받아들이지만, `deviceId`는
 * 항상 요구함.
 *
 * 향후 호환 필드:
 *  - [restaurantId] 상관관계용 에코; 플레이어는 이를 사용해 이벤트가 올바른
 *    디바이스로 라우팅되었는지 정합성 확인.
 *  - [updatedAt] 스케줄이 변경된 ISO-8601 타임스탬프; 소비자가 순서가
 *    뒤섞인 이벤트를 무시할 수 있게 해줌.
 *  - [playlist] 인라인 플레이리스트용 예약(재조회 왕복 회피). 현재 `null` —
 *    형제 sub-AC가 플레이리스트 빌더가 연결되면 이를 채울 예정. 이 모듈이
 *    플레이리스트 빌드 모듈과 분리되도록 `Any?`로 타입 지정.
 */
data class PlaylistUpdatedPayload(
    val deviceId: String,
    val restaurantId: String? = null,
    val updatedAt: Instant? = Instant.now(),
    val playlist: Any? = null,
)
