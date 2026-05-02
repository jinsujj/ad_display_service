/**
 * AdSignage 관리자 웹용 경량 API 클라이언트.
 *
 * 관리자 UI의 모든 HTTP 트래픽은 다음을 위해 이 모듈을 통해 흐른다:
 *   - API 베이스 URL을 한 곳에서 관리(NEXT_PUBLIC_API_BASE_URL),
 *   - JSON / 에러 처리를 일관되게 유지,
 *   - 보호된 엔드포인트에 대한 브라우저 호출에 JWT 인증(Bearer)을 자동
 *     첨부(Spring `SecurityConfig`는 `/api/ads/**`에 대해 이를 요구함),
 *   - 동일 출처(stream.owl-dev.me의 nginx 뒤)와 로컬 개발 시 교차 출처
 *     (예: http://192.168.0.24:8080) 모두에서 동작.
 *
 * Next.js의 내장 `fetch` 폴리필 덕분에 Server Component에서도 이 헬퍼를
 * 직접 호출할 수 있다. 관리 뷰는 Next 데이터 캐시가 아닌 실시간 운영
 * 데이터가 필요하므로 모든 요청에 `cache: 'no-store'`를 표시한다.
 */

const RAW_BASE_URL = process.env.NEXT_PUBLIC_API_BASE_URL ?? "";

/**
 * 관리자 웹이 로그인 후 광고주 JWT를 보관하는 localStorage 키.
 * 코드베이스 전반(`web/lib/videos.ts`, 인증 UI 주석, 폼 카피)에 동일하게
 * 사용되어 단일 키로 "로그인" 상태를 유지한다.
 */
export const AUTH_TOKEN_STORAGE_KEY = "adsignage_auth_token";

/**
 * `localStorage`에 저장된 JWT를 읽는다. 서버 사이드(Server Component, SSR
 * fetch)와 스토리지를 사용할 수 없는 환경(일부 브라우저의 시크릿 모드는
 * 접근 시 throw)에서는 `null`을 반환한다.
 */
export function readStoredAuthToken(): string | null {
  if (typeof window === "undefined") return null;
  try {
    return window.localStorage.getItem(AUTH_TOKEN_STORAGE_KEY);
  } catch {
    return null;
  }
}

/**
 * 끝의 슬래시를 모두 제거한 설정된 백엔드 베이스 URL을 반환하거나,
 * "동일 출처"를 의미하는 빈 문자열("")을 반환한다(예: `/api/devices`와 같은 요청).
 */
export function getApiBaseUrl(): string {
  return RAW_BASE_URL.replace(/\/+$/, "");
}

/**
 * API 베이스 URL과 요청 경로를 합친다. 경로 앞의 슬래시 유무와 관계없이
 * 결과는 항상 정확히 하나의 구분자를 갖는다.
 */
export function apiUrl(path: string): string {
  const base = getApiBaseUrl();
  const suffix = path.startsWith("/") ? path : `/${path}`;
  return `${base}${suffix}`;
}

export class ApiError extends Error {
  readonly status: number;
  readonly url: string;
  readonly body: unknown;

  constructor(message: string, status: number, url: string, body: unknown) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.url = url;
    this.body = body;
  }
}

type ApiFetchInit = Omit<RequestInit, "body"> & {
  body?: unknown;
  /** Next의 데이터 캐시를 강제 비활성화한다; 실시간 운영 데이터 기본값은 true. */
  noStore?: boolean;
  /**
   * `Authorization: Bearer <token>`으로 전송할 명시적 JWT. `localStorage`
   * 폴백을 덮어쓴다. 이 호출에 한해 인증 첨부를 건너뛰려면 `null`을 전달
   * (예: 토큰을 절대 보내면 안 되는 공개 플레이어 API).
   */
  bearerToken?: string | null;
};

/**
 * 저수준 헬퍼: 백엔드에 JSON 요청을 수행하고 2xx가 아닐 경우 [ApiError]를
 * throw한다. 호출자는 아래의 타입드 래퍼를 사용한다.
 *
 * 인증 첨부 정책:
 *   - 호출자가 `bearerToken`을 전달하면 그 값이 우선한다(`null`은 제외 처리).
 *   - 그 외에는 브라우저에서 `localStorage.adsignage_auth_token`으로 폴백되어
 *     인증이 필요한 관리자 엔드포인트(예: `PUT /api/ads/{id}/schedule`)가
 *     로그인 후 그대로 동작한다.
 *   - 서버 사이드(Server Component / SSR)에는 `localStorage`가 없으므로
 *     호출자가 명시하지 않는 한 아무것도 첨부되지 않는다.
 *   - `init.headers`에 미리 설정된 `Authorization` 헤더는 위 모든 경우보다
 *     항상 우선한다.
 */
export async function apiFetch<T>(path: string, init: ApiFetchInit = {}): Promise<T> {
  const url = apiUrl(path);
  const { body, headers, noStore = true, bearerToken, ...rest } = init;

  const finalHeaders: Record<string, string> = {
    Accept: "application/json",
    ...(body !== undefined ? { "Content-Type": "application/json" } : {}),
    ...((headers as Record<string, string> | undefined) ?? {}),
  };

  // 호출자가 Authorization 헤더를 직접 지정하지 않은 경우에만 JWT를 첨부.
  // `bearerToken === null`은 의도적인 제외(공개 엔드포인트용).
  const hasExplicitAuth = Object.keys(finalHeaders).some(
    (k) => k.toLowerCase() === "authorization",
  );
  if (!hasExplicitAuth && bearerToken !== null) {
    const token = bearerToken ?? readStoredAuthToken();
    if (token) {
      finalHeaders.Authorization = `Bearer ${token}`;
    }
  }

  const response = await fetch(url, {
    ...rest,
    headers: finalHeaders,
    body: body !== undefined ? JSON.stringify(body) : undefined,
    cache: noStore ? "no-store" : rest.cache,
  });

  if (!response.ok) {
    let errorBody: unknown = null;
    try {
      errorBody = await response.json();
    } catch {
      try {
        errorBody = await response.text();
      } catch {
        errorBody = null;
      }
    }
    throw new ApiError(
      `Request failed (${response.status}) for ${url}`,
      response.status,
      url,
      errorBody,
    );
  }

  // 204 No Content / 빈 본문 -> undefined를 T로 반환
  if (response.status === 204) return undefined as T;
  const text = await response.text();
  if (!text) return undefined as T;
  return JSON.parse(text) as T;
}
