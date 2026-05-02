/**
 * 플레이어 → 백엔드 재생 이벤트 보고(AC 20202 Sub-AC 2).
 *
 * Spring `PlayEventController`가 도입한 와이어 계약의 클라이언트 측을 담당:
 *
 *   POST /api/devices/{deviceId}/play-events
 *     Content-Type: application/json
 *     {
 *       "adId":       "<uuid>",
 *       "eventType":  "STARTED" | "FINISHED",
 *       "occurredAt": "<ISO-8601>"             // 선택
 *     }
 *
 *     ⇒ 성공 시 201 Created(플레이어는 의도적으로 본문을 무시).
 *
 * 플레이어 React 컴포넌트에서 `apiFetch`를 직접 호출하지 않고 별도 모듈로
 * 분리한 이유:
 *
 *  - **핫패스 견고성.** 이 엔드포인트는 fire-and-forget 텔레메트리다. 백엔드
 *    POST 성공 여부와 관계없이 화면은 다음 광고를 계속 재생해야 한다. 호출을
 *    한곳에 모아두면 매 `<video onPlay>` / `<video onEnded>` 호출 지점마다
 *    반복하는 대신 *하나*의 swallow-and-log 에러 핸들러를 작성할 수 있고,
 *    향후 변경(백오프 재시도, 언로드 시 beacon)도 한 곳에 적용된다.
 *
 *  - **인증 경계.** 플레이어 API는 설계상 비인증이다(`SecurityConfig`의
 *    `/api/devices/*\/play-events` 허용 목록 참조). 공유 `apiFetch`는 동일
 *    브라우저 프로필에서 관리자 웹이 열려 있을 때 기본적으로 `localStorage`의
 *    JWT를 첨부하는데, 이는 시맨틱 누수가 된다(플레이어는 광고주 토큰을 절대
 *    가지면 안 됨). `bearerToken: null`을 전달하면 인증 첨부에서 깔끔하게
 *    제외된다.
 *
 *  - **테스트 용이성.** 작은 인터페이스의 순수 함수는 형제 플레이어 라이브러리
 *    (`roundRobin.test.mjs`, `dailyCount.test.mjs`)에서 사용된 vendored-impl
 *    패턴과 동일하게 `node --test`에서 손쉽게 커버된다.
 *
 *  - **SSR 안전성.** 플레이어 페이지(브라우저 전용)와 잠재적인 server-component
 *    프리렌더 양쪽에서 재사용된다. 플레이어 라우트는 client component이므로
 *    서버에서는 `fetch`가 항상 no-op이지만, 호출을 여기 구조화해두면 계약이
 *    명확해진다.
 */

import { apiFetch, ApiError } from "./api";

/**
 * 플레이어가 보고할 수 있는 두 가지 재생 이벤트 신호. 백엔드의
 * `me.owldev.adsignage.domain.playevent.PlayEventType`과 동일하므로 와이어
 * 형태가 JPA enum과 JSON 동형성을 유지한다.
 */
export type PlayEventType = "STARTED" | "FINISHED";

/** POST /api/devices/{deviceId}/play-events의 요청 본문. */
export interface PlayEventRequest {
  adId: string;
  eventType: PlayEventType;
  /**
   * 디바이스에서 이벤트가 발생한 ISO-8601 instant. 백엔드는 이 필드를
   * 선택으로 취급하여 자체 시계로 폴백한다. 그래도 헬퍼는 기본값으로 하나를
   * 전송해, 시계 편차 분석 시 비교할 매칭 쌍이 항상 존재하도록 한다.
   */
  occurredAt?: string;
}

/**
 * 서버 응답 형태(저장된 행의 에코). 플레이어는 핫패스에서 이를 무시한다.
 * 향후 디버그 오버레이가 필요할 때 서버가 찍은 `receivedAt`을 표시할 수
 * 있도록 export 한다.
 */
export interface PlayEventResponse {
  id: string;
  deviceId: string;
  adId: string;
  eventType: PlayEventType;
  occurredAt: string;
  receivedAt: string;
}

/**
 * 단일 재생 이벤트를 백엔드로 POST한다.
 *
 * 동작 계약:
 *  - 2xx에서 저장된 [PlayEventResponse]를 반환한다. 2xx가 아니면 `./api`에서
 *    재export된 [ApiError]를 throw하므로, 실패에 *관심*이 있는 호출자는
 *    캐치할 수 있다. 다만 플레이어용 권장 패턴은 [reportPlayEvent]로,
 *    로깅 후 swallow한다.
 *  - `bearerToken: null` — 명시적 인증 옵트아웃. 동일 브라우저에서 형제
 *    관리자 탭이 localStorage에 토큰을 가진 경우에도 플레이어 트래픽은
 *    광고주 JWT를 운반해서는 안 된다.
 *  - `noStore: true` — 텔레메트리 POST는 당연히 캐시 대상이 아니지만, 이
 *    플래그를 켜면 Next.js(Server Components / Route Handlers)에 응답을
 *    메모이즈하지 말라고 알리는 효과도 있어 `apiFetch`의 나머지와 일관된다.
 *
 * 순수 / React 의존성 없음 — 그래서 단위 테스트 미러가 DOM 없이도 실행할
 * 수 있다. 실제 `<video>` 연결은 `PlayerClient.tsx`에 있다.
 */
export async function postPlayEvent(
  deviceId: string,
  body: PlayEventRequest,
): Promise<PlayEventResponse> {
  if (!deviceId) throw new Error("deviceId is required");
  if (!body.adId) throw new Error("body.adId is required");
  if (body.eventType !== "STARTED" && body.eventType !== "FINISHED") {
    throw new Error(
      `body.eventType must be 'STARTED' or 'FINISHED' (got '${body.eventType}')`,
    );
  }

  const payload: PlayEventRequest = {
    adId: body.adId,
    eventType: body.eventType,
    // 기본값으로 현재 디바이스의 벽시계 시각을 사용해 서버가 매칭 쌍
    // (디바이스의 `occurredAt`, 도착 시 찍는 `receivedAt`)을 갖도록 한다.
    // 향후 디버그 오버레이는 둘을 빼서 시계 편차를 노출할 수 있고, 서버
    // 로그를 모니터링하는 운영자는 모든 클라이언트에서 선택 필드를 활성화하지
    // 않아도 디바이스 자체 타임스탬프를 받을 수 있다.
    occurredAt: body.occurredAt ?? new Date().toISOString(),
  };

  return apiFetch<PlayEventResponse>(
    `/api/devices/${encodeURIComponent(deviceId)}/play-events`,
    {
      method: "POST",
      body: payload,
      // 플레이어는 익명 — 저장된 광고주 JWT를 절대 첨부하지 않는다.
      bearerToken: null,
    },
  );
}

/**
 * 플레이어 핫패스를 위한 [postPlayEvent]의 fire-and-forget 래퍼.
 * `<video onPlay>`와 `<video onEnded>`에서 사용된다 — 운영자의 기대는
 * 텔레메트리 POST가 실패해도(네트워크 일시 단절, 백엔드 재배포, 일시적 5xx)
 * 화면이 다음 광고를 계속 재생하는 것이다. 모든 에러는 warn 레벨로 로깅되고
 * swallow된다.
 *
 * 항상 resolve하는 Promise를 반환한다. await은 선택이며, 개발 중 로그문을
 * 체이닝하기 위한 용도로만 존재한다.
 *
 * [postPlayEvent] 내부에서 항상 swallow하지 않고 별도 함수로 둔 이유: 향후
 * 분석 대시보드나 재시도 큐가 결정에 활용할 수 있는 타입드 `ApiError`가
 * 필요할 수 있기 때문이다. 저수준 헬퍼에 throw 계약을 유지하면, 플레이어
 * 코드가 모든 `<video>` 콜백을 try/catch로 감쌀 필요 없이 그 옵션을 보존할
 * 수 있다.
 */
export async function reportPlayEvent(
  deviceId: string,
  body: PlayEventRequest,
): Promise<PlayEventResponse | null> {
  try {
    return await postPlayEvent(deviceId, body);
  } catch (err) {
    // 실제 실패의 주의를 빼앗지 않으면서 WebView 개발자 도구에서 분류할 수
    // 있을 만큼만 로깅. ApiError는 URL과 status를 운반하며, 그 외는 비구조적
    // Error로 다시 throw된다.
    if (err instanceof ApiError) {
      // eslint-disable-next-line no-console
      console.warn(
        "[playEvents] reportPlayEvent failed",
        { deviceId, adId: body.adId, eventType: body.eventType, status: err.status, url: err.url },
      );
    } else {
      // eslint-disable-next-line no-console
      console.warn(
        "[playEvents] reportPlayEvent threw",
        { deviceId, adId: body.adId, eventType: body.eventType, err },
      );
    }
    return null;
  }
}

/** 플레이어 페이지가 단일 모듈에서 모든 것을 import할 수 있도록 재export. */
export { ApiError };
