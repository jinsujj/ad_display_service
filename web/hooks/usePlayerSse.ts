"use client";

/**
 * usePlayerSse — 플레이어 페이지의 SSE 구독을 위한 React 훅.
 *
 * AC 5, Sub-AC 4 (Player/Android):
 *   "플레이어 페이지에 SSE 구독을 구현하여, 재매핑 시 수동 새로고침 없이
 *    디바이스가 즉시 스케줄을 다시 로드하도록 한다."
 *
 * 이 훅이 하는 일:
 *   1. 다음 엔드포인트에 대해 장수명 `EventSource`를 연다:
 *        GET /api/devices/{deviceId}/stream
 *      (Spring `DeviceStreamController`가 제공하며, timeout=0으로 연결을
 *      유지하고 `CONNECTED` 핸드셰이크 이벤트를 발행한다.)
 *      이미 배포된 Android WebView를 위해 백엔드에는 레거시
 *      `/api/devices/{deviceId}/events` 컨트롤러도 마운트되어 있으며, 새
 *      플레이어는 AC 1에 따라 `/stream`을 사용한다.
 *   2. 백엔드 `SseEventNames`에 정의된 와이어 이벤트를 수신한다:
 *        - `CONNECTED`        — 핸드셰이크; 상태를 "open"으로 전환.
 *        - `MAPPING_CHANGED`  — 디바이스가 새 식당으로 막 재매핑되었음;
 *                               스케줄을 *반드시* 다시 로드해야 한다.
 *        - `PLAYLIST_UPDATE`  — 플레이리스트 내용이 변경됨(광고 추가,
 *                               스케줄 편집); 마찬가지로 다시 로드.
 *   3. MAPPING_CHANGED 또는 PLAYLIST_UPDATE 이벤트가 도착할 때마다
 *      소비자가 제공한 `onScheduleReload(reason)` 콜백을 호출한다. 이
 *      콜백이 실제로 `/api/devices/{id}/playlist`를 재조회하고 플레이어의
 *      활성 플레이리스트를 교체한다 — 수동 새로고침이나 WebView 리로드
 *      없이.
 *   4. EventSource가 에러로 끊기면(네트워크 일시 단절, nginx 재시작, 서버
 *      재배포) 지수 백오프로 자동 재연결한다. 브라우저 기본 EventSource도
 *      서버가 보내는 `retry:` 필드로 자체 재연결하지만, 하드 에러에는
 *      포기한다 — 그래서 그 위에 명시적 재연결 루프를 추가해 식당의
 *      불안정한 WiFi에서도 데모가 견고하게 동작하도록 한다.
 *   5. 컴포넌트가 언마운트되거나 `deviceId`가 변경되면 EventSource와 보류
 *      중인 재연결 타이머를 정리한다 — 그러지 않으면 백엔드
 *      `DeviceSseRegistry`에 누수된 연결이 쌓인다.
 *
 * 페이지 컴포넌트에 내장하지 않고 훅으로 만든 이유:
 *   - SSE 부수효과 라이프사이클을 격리해 테스트 가능하게 유지.
 *   - 플레이어 페이지를 선언적으로 유지(상태만 받고, 변경이 있으면 훅이
 *     `onScheduleReload`를 호출).
 *   - 형제 Sub-AC(라운드 로빈 재생, 스플래시 화면, 비디오 range 플레이어)가
 *     SSE 배관을 다시 구현하지 않고 이 훅을 합성할 수 있다.
 *
 * 와이어 계약 참조(교차 검증을 위해 그대로 유지):
 *   event: CONNECTED
 *   data:  { "deviceId": "...", "serverTime": "..." }
 *
 *   event: MAPPING_CHANGED
 *   data:  { "deviceId": "...", "restaurantId": "...",
 *            "assignmentId": "...", "assignedAt": "..." }
 *
 *   event: PLAYLIST_UPDATE
 *   data:  { "deviceId": "...", ... }   // 예약됨; 최소 형태.
 */

import { useEffect, useRef, useState } from "react";
import { apiUrl } from "@/lib/api";

/** 와이어 이벤트 이름 — 백엔드 `SseEventNames`와 일치해야 함. */
export const SSE_EVENT_CONNECTED = "CONNECTED" as const;
export const SSE_EVENT_MAPPING_CHANGED = "MAPPING_CHANGED" as const;
export const SSE_EVENT_PLAYLIST_UPDATE = "PLAYLIST_UPDATE" as const;

/** UI에 반영할 SSE 연결 라이프사이클. */
export type SsePlayerStatus =
  | "idle" // 훅이 아직 연결되지 않음(예: deviceId가 빈 값)
  | "connecting" // EventSource 생성됨, 아직 핸드셰이크 없음
  | "open" // CONNECTED 수신 또는 readyState === OPEN
  | "reconnecting" // 끊김, 재시도 대기
  | "error"; // EventSource 미지원 또는 치명적 실패

/** 소비자의 `onScheduleReload`이 호출된 이유. */
export type ReloadReason =
  /**
   * 페이지 마운트 — 플레이어 라우트가 막 렌더된 상태. AC 7, Sub-AC 1은
   * SSE를 기다리지 *않는* 마운트 시 초기 플레이리스트 조회를 요구한다. 이
   * 사유는 이 훅이 아니라 플레이어 페이지의 자체 마운트 이펙트가 제공하므로,
   * 동일한 `onScheduleReload` 콜백이 모든 조회 트리거를 처리한다. 타입을
   * 망라적으로 유지하고 도구(switch/case, exhaustive-deps)가 모든 곳에서
   * 커버하도록 여기 나열한다.
   */
  | { kind: "initial" }
  | { kind: "mapping_changed"; restaurantId: string; assignmentId: string }
  /**
   * AC 60201, Sub-AC 1: PLAYLIST_UPDATE를 수신해 파싱한 경우. 관대한
   * `payload`는 백엔드가 보낸 무엇이든 운반한다(현재는 `{ deviceId, ... }`,
   * 전방 호환을 위한 선택적 인라인 `playlist` 예약 포함). 따라서 소비자의
   * `setState`/리듀서는 다음을 선택할 수 있다:
   *   - 인라인 플레이리스트를 직접 적용(빠른 경로, 재조회 없음), 또는
   *   - `/api/devices/{id}/playlist` 재조회로 폴백.
   * `payload`는 SSE `data:` 라인이 누락되었거나 파싱 불가일 때만 null이며,
   * 이 경우 소비자는 재조회해야 한다.
   */
  | { kind: "playlist_update"; payload: PlaylistUpdatePayload | null }
  /**
   * 초기 연결 — 첫 성공한 `CONNECTED` 핸드셰이크 이후 정확히 한 번 발생.
   * 페이지의 마운트 시 조회에 더해 belt-and-braces 재조회 역할을 하며,
   * 마운트 조회가 실패한 후 SSE 채널이 나중에 백엔드의 건강성을 확인할 때
   * 유용하다.
   */
  | { kind: "connected" };

/**
 * 디코딩된 `MAPPING_CHANGED` 페이로드. 백엔드 `MappingChangedPayload`
 * (Kotlin)와 동일 — backend/src/main/kotlin/me/owldev/adsignage/sse/SseEvents.kt 참조.
 */
export interface MappingChangedPayload {
  deviceId: string;
  restaurantId: string;
  assignmentId: string;
  assignedAt: string;
}

/**
 * 디코딩된 `PLAYLIST_UPDATE` 페이로드(AC 60201 Sub-AC 1).
 *
 * 이 이벤트의 백엔드 와이어 계약은 "예약됨; 최소 형태"로 문서화되어 있다 —
 * 최소한 `deviceId`를 운반하므로, 플레이어가 이벤트가 이 연결을 대상으로
 * 하는지 확인할 수 있다(다층 방어: SSE 이미터는 디바이스별이지만 프록시
 * 계층의 공유 채널이 잘못 전달할 수 있다).
 *
 * 전방 호환 선택 필드:
 *   - `playlist`     → 인라인 신규 플레이리스트; 존재하면 플레이어는 재조회
 *                      없이 `setPlaylistState`로 직접 적용할 수 있다.
 *   - `updatedAt`    → 스케줄 변경 시각의 ISO-8601 타임스탬프; 소비자가
 *                      순서가 뒤바뀐 이벤트를 무시할 수 있게 한다.
 *   - `restaurantId` → 상관관계용 에코일 뿐; 필수 아님.
 *
 * 인라인 `playlist`는 `lib/playlist.ts`의 `DevicePlaylist`와 형태가 같지만,
 * 훅이 playlist 모듈과 분리되도록 의도적으로 `unknown`으로 타입을 둔다 —
 * 소비자(PlayerClient)가 상태에 커밋하기 전에 기존 플레이리스트 정규화기로
 * 형태를 검증해 재사용한다.
 */
export interface PlaylistUpdatePayload {
  deviceId: string;
  restaurantId?: string | null;
  playlist?: InlinePlaylist | null;
  updatedAt?: string | null;
}

/**
 * `PLAYLIST_UPDATE` 이벤트가 운반하는 느슨한 인라인 플레이리스트 형태.
 * `GET /api/devices/{id}/playlist`가 반환하는 JSON과 형태가 같아, 소비자가
 * 재조회 경로에서 사용하는 동일한 정규화기에 그대로 통과시킬 수 있다.
 * 예상치 못한 필드가 파싱을 실패시키지 않도록 키를 `unknown`으로 유지 —
 * 다운스트림 정규화기가 표준 `DevicePlaylist`로 다듬는 책임을 진다.
 */
export interface InlinePlaylist {
  deviceId?: string;
  restaurantId?: string | null;
  ads?: unknown[] | null;
  items?: unknown[] | null;
  fetchedAt?: string;
}

export interface UsePlayerSseOptions {
  /** 필수 디바이스 UUID. 이 값이 비어 있는 동안 훅은 no-op이다. */
  deviceId: string;
  /**
   * 플레이어가 플레이리스트를 다시 조회해야 할 때 호출된다. 발생 시점:
   *   - 초기 연결 시 한 번(로드 시 페이지에 뭔가 렌더링되도록);
   *   - 매 MAPPING_CHANGED;
   *   - 매 PLAYLIST_UPDATE.
   * 소비자는 보통 `await fetchPlaylist(deviceId)`을 수행한 뒤 `<video>`
   * 소스를 교체한다 — 이것이 "재매핑 시 수동 새로고침 없이 디바이스가 즉시
   * 스케줄을 다시 로드한다"는 동작이다.
   */
  onScheduleReload: (reason: ReloadReason) => void;
  /**
   * 디바이스가 재매핑될 때의 선택적 알림. 페이지가 플레이리스트 왕복을
   * 기다리지 않고 "Switching to {restaurant}…" 스플래시를 표시하는 데
   * 유용하다(백엔드는 이미 SSE 페이로드에 새 restaurantId를 포함한다).
   */
  onMappingChanged?: (payload: MappingChangedPayload) => void;
  /**
   * 초기 재연결 지연(ms)을 덮어쓴다. 훅은 이 값에서 [maxReconnectDelayMs]까지
   * 지수 백오프한다. 기본 1000 ms.
   */
  initialReconnectDelayMs?: number;
  /** 지수 백오프의 상한. 기본 30_000 ms. */
  maxReconnectDelayMs?: number;
  /**
   * 자세한 콘솔 로깅 활성화(라이브 데모 중에 유용 — 운영자가 WebView 콘솔을
   * 보며 재매핑 이벤트가 도착하는 것을 확인 가능). 기본 false.
   */
  debug?: boolean;
}

export interface UsePlayerSseResult {
  /** 현재 SSE 라이프사이클 상태 — 상태 pill을 여기에 연결. */
  status: SsePlayerStatus;
  /**
   * 단조 증가 카운터. 소비자의 `onScheduleReload`이 호출될 때마다 증가한다.
   * 명령형 콜백보다 선언적 재조회 패턴을 선호하는 호출자에게 `useEffect`
   * 의존성으로 유용(예: `useEffect(() => fetchPlaylist(), [reloadCounter])`).
   */
  reloadCounter: number;
  /** 마지막 성공 이후의 자동 재연결 시도 횟수. */
  reconnectAttempts: number;
  /** 마지막 `MAPPING_CHANGED` 이벤트를 수신한 시각(ms epoch), 또는 0. */
  lastMappingChangeAt: number;
  /**
   * 현재 연결을 끊고 다시 여는 명령형 트리거 — 플레이어 스플래시 화면의
   * "Reconnect now" 디버그 버튼에 유용하다.
   */
  reconnectNow: () => void;
}

/**
 * 플레이어 페이지를 [deviceId]의 백엔드 SSE 스트림에 구독시키고, 스케줄을
 * 다시 조회해야 할 때마다 `onScheduleReload`를 호출한다.
 */
export function usePlayerSse(options: UsePlayerSseOptions): UsePlayerSseResult {
  const {
    deviceId,
    onScheduleReload,
    onMappingChanged,
    initialReconnectDelayMs = 1000,
    maxReconnectDelayMs = 30_000,
    debug = false,
  } = options;

  const [status, setStatus] = useState<SsePlayerStatus>("idle");
  const [reloadCounter, setReloadCounter] = useState<number>(0);
  const [reconnectAttempts, setReconnectAttempts] = useState<number>(0);
  const [lastMappingChangeAt, setLastMappingChangeAt] = useState<number>(0);

  // 최신 콜백을 ref에 보관해 소비자의 함수 정체성이 바뀔 때마다 재구독하지
  // 않고 EventSource를 렌더 간에 열어둘 수 있게 한다.
  const onScheduleReloadRef = useRef(onScheduleReload);
  const onMappingChangedRef = useRef(onMappingChanged);
  useEffect(() => {
    onScheduleReloadRef.current = onScheduleReload;
  }, [onScheduleReload]);
  useEffect(() => {
    onMappingChangedRef.current = onMappingChanged;
  }, [onMappingChanged]);

  // 수동 재연결 신호 — connect-effect가 다시 실행되도록 증가시킨다.
  const [reconnectNonce, setReconnectNonce] = useState<number>(0);
  const reconnectNow = () => setReconnectNonce((n) => n + 1);

  useEffect(() => {
    if (!deviceId) {
      setStatus("idle");
      return;
    }

    if (typeof window === "undefined" || typeof EventSource === "undefined") {
      // SSR이거나 EventSource가 없는 런타임(매우 오래된 WebView).
      // 해커톤 WebView(Android System WebView)는 지원하므로, 이는 주로 단위
      // 테스트 / 빌드 시 프리렌더를 위한 안전망이다.
      setStatus("error");
      return;
    }

    let cancelled = false;
    let es: EventSource | null = null;
    let reconnectTimer: ReturnType<typeof setTimeout> | null = null;
    let attempts = 0;
    let firstConnectFired = false;

    // AC 1 — `/events`가 아닌 `/stream`을 구독. 백엔드는 두 라우트를 모두
    // 노출하며(DeviceStreamController + 레거시 DeviceSseController) 와이어
    // 계약(CONNECTED 핸드셰이크, MAPPING_CHANGED, PLAYLIST_UPDATE)은 동일하다.
    // 따라서 여기 URL만 바꾸면 한 줄 교체로 충분하다.
    const url = apiUrl(`/api/devices/${encodeURIComponent(deviceId)}/stream`);

    const log = (...args: unknown[]) => {
      if (debug) console.log("[usePlayerSse]", ...args);
    };

    const triggerReload = (reason: ReloadReason) => {
      setReloadCounter((n) => n + 1);
      try {
        onScheduleReloadRef.current(reason);
      } catch (err) {
        // 소비자 예외가 SSE 파이프라인을 죽이게 두지 않는다.
        console.error("[usePlayerSse] onScheduleReload threw:", err);
      }
    };

    const scheduleReconnect = () => {
      if (cancelled) return;
      attempts += 1;
      setReconnectAttempts(attempts);
      const delay = Math.min(
        maxReconnectDelayMs,
        initialReconnectDelayMs * Math.pow(2, Math.min(attempts - 1, 6)),
      );
      log(`scheduling reconnect in ${delay}ms (attempt ${attempts})`);
      setStatus("reconnecting");
      reconnectTimer = setTimeout(() => {
        reconnectTimer = null;
        if (!cancelled) connect();
      }, delay);
    };

    const connect = () => {
      if (cancelled) return;
      try {
        log("connecting to", url);
        setStatus("connecting");
        es = new EventSource(url);

        // 브라우저의 onopen은 TCP/HTTP 레벨 open에서 발생한다. CONNECTED
        // 이벤트를 표준 "채널 정상" 신호로 취급하는데, 이는 서버 측 핸들러가
        // 실행되었음을 확인해주기 때문이다. 그래도 onopen을 듣는 이유는,
        // 서버가 핸드셰이크를 늦게 보내더라도 상태가 "connecting"에서
        // 빠져나오게 하기 위함이다.
        es.onopen = () => {
          log("EventSource opened");
          // 여기서 attempts를 리셋하지 않는다 — 첫 이벤트(CONNECTED)를
          // 기다려 *서버*(프록시뿐 아니라)가 살아있음을 확인한다.
        };

        es.onerror = (ev) => {
          // EventSource는 일시적 에러(readyState === CONNECTING일 때) 자체로
          // 자동 재연결한다. CLOSED 같은 종결 상태에서는 우리가 인계해야 한다.
          log("EventSource error", ev, "readyState=", es?.readyState);
          if (!es) return;
          if (es.readyState === EventSource.CLOSED) {
            es.close();
            es = null;
            scheduleReconnect();
          }
          // CONNECTING (1)에서는 브라우저가 한 번 시도하게 둔다. 계속 실패하면
          // 결국 CLOSED로 전이되어 위 분기가 인계받는다.
        };

        // CONNECTED — 핸드셰이크. 백엔드는 새 이미터에서 즉시 이를 항상 보낸다.
        // 이를 사용해 상태를 -> open으로 전환하고 초기 스케줄 리로드를
        // 발생시켜, 별도 이펙트 없이 플레이어가 첫 페인트에 무언가를 그리게
        // 한다.
        es.addEventListener(SSE_EVENT_CONNECTED, (e: MessageEvent) => {
          log("CONNECTED received", e.data);
          attempts = 0;
          setReconnectAttempts(0);
          setStatus("open");
          if (!firstConnectFired) {
            firstConnectFired = true;
            triggerReload({ kind: "connected" });
          }
        });

        // MAPPING_CHANGED — Sub-AC 4가 존재하는 이유인 바로 그 이벤트.
        // 페이로드를 디코드해 선택적 onMappingChanged 훅에 넘긴(그래야
        // 페이지가 새 restaurantId로 전환 스플래시를 즉시 그릴 수 있음) 뒤,
        // 주 콜백으로 전체 스케줄 리로드를 트리거한다.
        es.addEventListener(SSE_EVENT_MAPPING_CHANGED, (e: MessageEvent) => {
          log("MAPPING_CHANGED received", e.data);
          const payload = parseMappingChangedPayload(e.data);
          setLastMappingChangeAt(Date.now());
          if (payload && onMappingChangedRef.current) {
            try {
              onMappingChangedRef.current(payload);
            } catch (err) {
              console.error(
                "[usePlayerSse] onMappingChanged threw:",
                err,
              );
            }
          }
          triggerReload({
            kind: "mapping_changed",
            restaurantId: payload?.restaurantId ?? "",
            assignmentId: payload?.assignmentId ?? "",
          });
        });

        // PLAYLIST_UPDATE — 다른 어떤 이유로든 스케줄 내용이 바뀜
        // (광고주가 새 광고 추가, 스케줄 편집 등). AC 60201 Sub-AC 1:
        // 들어오는 `data:` JSON을 파싱해 타입드 페이로드를 소비자에게 넘겨,
        // setState/리듀서가 인라인 플레이리스트를 직접 적용하거나 권위
        // 있게 재조회하도록 한다.
        es.addEventListener(SSE_EVENT_PLAYLIST_UPDATE, (e: MessageEvent) => {
          log("PLAYLIST_UPDATE received", e.data);
          const payload = parsePlaylistUpdatePayload(e.data);
          // 다층 방어: SSE 이벤트가 어떤 이유로든 다른 디바이스용으로 전달된
          // 경우(프록시 잘못된 라우팅, 레지스트리 버그 등) 경고를 로그하되
          // 그래도 리로드를 트리거한다 — 재조회 경로가 디바이스 스코프의
          // `/api/devices/{thisDeviceId}/playlist` 엔드포인트를 호출하므로,
          // 잘못된 디바이스의 데이터가 상태로 새지 않는다.
          if (payload && payload.deviceId && payload.deviceId !== deviceId) {
            console.warn(
              "[usePlayerSse] PLAYLIST_UPDATE deviceId mismatch:",
              { eventDeviceId: payload.deviceId, expected: deviceId },
            );
          }
          triggerReload({ kind: "playlist_update", payload });
        });
      } catch (err) {
        log("connect threw", err);
        setStatus("error");
        scheduleReconnect();
      }
    };

    connect();

    return () => {
      cancelled = true;
      if (reconnectTimer) {
        clearTimeout(reconnectTimer);
        reconnectTimer = null;
      }
      if (es) {
        log("closing EventSource on cleanup");
        es.close();
        es = null;
      }
      setStatus("idle");
    };
    // `reconnectNonce`는 `reconnectNow()`가 이 이펙트를 다시 실행해
    // EventSource를 끊고 다시 열도록 한다. 나머지 의존성은 변경 시 열린
    // 연결을 정당하게 무효화하는 구성 값이다.
  }, [
    deviceId,
    initialReconnectDelayMs,
    maxReconnectDelayMs,
    debug,
    reconnectNonce,
  ]);

  return {
    status,
    reloadCounter,
    reconnectAttempts,
    lastMappingChangeAt,
    reconnectNow,
  };
}

/**
 * MAPPING_CHANGED SSE 이벤트의 JSON 본문을 파싱한다. 관대함: 페이로드가
 * 형식 오류이면 null을 반환하므로, 호출자는 그래도 스케줄 리로드를 트리거할
 * 수 있다(리로드는 어차피 백엔드에서 권위 있는 상태를 조회한다).
 */
function parseMappingChangedPayload(
  raw: string,
): MappingChangedPayload | null {
  if (!raw) return null;
  try {
    const obj = JSON.parse(raw) as Partial<MappingChangedPayload>;
    if (
      typeof obj.deviceId === "string" &&
      typeof obj.restaurantId === "string" &&
      typeof obj.assignmentId === "string" &&
      typeof obj.assignedAt === "string"
    ) {
      return {
        deviceId: obj.deviceId,
        restaurantId: obj.restaurantId,
        assignmentId: obj.assignmentId,
        assignedAt: obj.assignedAt,
      };
    }
    return null;
  } catch {
    return null;
  }
}

/**
 * PLAYLIST_UPDATE SSE 이벤트의 JSON 본문 파싱 (AC 60201 Sub-AC 1).
 *
 * 의도적으로 관대 — PLAYLIST_UPDATE의 와이어 계약은 "예약됨; 최소 형태"로
 * 문서화되어 있고 형제 sub-AC가 새 필드(타임스탬프, 인라인 플레이리스트,
 * 변경 사유)를 추가하면서 진화할 수 있다. 파서는:
 *
 *   - 본문이 비었거나 유효한 JSON이 아니면 `null` 반환. 호출자는 그래도
 *     재 fetch를 트리거해야 한다 — 백엔드가 진실의 원천이며 SSE는 베스트
 *     에포트 신호링이다.
 *
 *   - `deviceId`가 추출 가능하면 그것을 최소한으로 담은 타입화된 객체
 *     반환. 선택 필드는 변경 없이 통과시켜 미래 백엔드 버전이 클라이언트
 *     동시 배포 없이 필드를 추가할 수 있게 한다.
 *
 *   - 인라인 `playlist`는 객체인지만 검증 — 소비자가 React 상태에
 *     커밋하기 전에 `lib/playlist.ts#normalisePlaylist`를 통과시켜야 알 수
 *     없는 추가 키가 잘리고 누락 필드가 합리적으로 기본값을 가진다.
 *
 * @param raw  SSE 이벤트의 `data:` 라인(EventSource가 이미 utf-8
 *             디코드했지만 여전히 JSON 문자열).
 */
function parsePlaylistUpdatePayload(
  raw: string,
): PlaylistUpdatePayload | null {
  if (!raw) return null;
  let obj: unknown;
  try {
    obj = JSON.parse(raw);
  } catch {
    return null;
  }
  if (!obj || typeof obj !== "object") return null;
  const rec = obj as Record<string, unknown>;

  const deviceId =
    typeof rec.deviceId === "string" && rec.deviceId.length > 0
      ? rec.deviceId
      : null;
  if (!deviceId) return null;

  const restaurantId =
    typeof rec.restaurantId === "string"
      ? rec.restaurantId
      : rec.restaurantId === null
        ? null
        : undefined;

  const updatedAt =
    typeof rec.updatedAt === "string" ? rec.updatedAt : undefined;

  // Inline playlist is optional and intentionally lightly validated — the
  // consumer re-runs the canonical normaliser before applying it.
  let playlist: InlinePlaylist | null | undefined;
  if (rec.playlist === null) {
    playlist = null;
  } else if (rec.playlist && typeof rec.playlist === "object") {
    playlist = rec.playlist as InlinePlaylist;
  }

  return {
    deviceId,
    ...(restaurantId !== undefined ? { restaurantId } : {}),
    ...(updatedAt !== undefined ? { updatedAt } : {}),
    ...(playlist !== undefined ? { playlist } : {}),
  };
}

/* ------------------------------------------------------ test exports */
/**
 * 단위 테스트용으로 노출된 내부 헬퍼. 공개 훅 API의 일부가 아님 —
 * 소비자는 이에 의존해서는 안 된다. 프로젝트에 JS 테스트 러너가 아직
 * 설정되지 않았기 때문에 별도 `__tests__` 파일이 아닌 여기서 재내보내며,
 * 이는 훅을 재구성하지 않고도 미래의 jest/vitest 스위트가 파서에
 * 접근할 수 있도록 유지한다.
 */
export const __test__ = {
  parseMappingChangedPayload,
  parsePlaylistUpdatePayload,
};
