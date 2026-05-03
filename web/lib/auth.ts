/**
 * AdSignage 어드민 — 광고주 인증 클라이언트.
 *
 * 백엔드 계약:
 *   - POST /api/auth/signup → 새 광고주 생성 (이메일+비밀번호). JWT를 *발급하지
 *     않음* — 가입 후 클라이언트는 별도로 /api/auth/login 을 호출해야 한다.
 *   - POST /api/auth/login  → JWT 발급. 응답에 accessToken / advertiserId /
 *     email / expiresInMs 가 담겨 옴.
 *
 * 토큰 보관:
 *   - 어드민 웹은 JWT를 `localStorage.adsignage_auth_token`에 둔다.
 *     이 키는 [api.ts]의 `AUTH_TOKEN_STORAGE_KEY` 와 동일하므로 `apiFetch`가
 *     자동으로 `Authorization: Bearer …` 헤더를 첨부한다.
 *   - 광고주 신원(이메일/ID)은 `localStorage.adsignage_auth_user`에
 *     JSON으로 저장 — 헤더의 환영 표시와 자동 redirect 결정에 쓰인다.
 *   - 두 키 모두 [logout]이 한 번에 비운다.
 */

import { ApiError, AUTH_TOKEN_STORAGE_KEY, apiFetch } from "./api";

/** 어드민 웹이 저장하는 광고주 신원의 localStorage 키. */
export const AUTH_USER_STORAGE_KEY = "adsignage_auth_user";

/* ------------------------------------------------------ wire shapes */

export interface SignupRequest {
  email: string;
  password: string;
}

export interface SignupResponse {
  advertiserId: string;
  email: string;
  createdAt: string; // ISO-8601
}

export interface LoginRequest {
  email: string;
  password: string;
}

export interface LoginResponse {
  accessToken: string;
  tokenType: string; // "Bearer"
  expiresInMs: number;
  advertiserId: string;
  email: string;
}

/** localStorage 에 저장하는 광고주 신원. */
export interface StoredAuthUser {
  advertiserId: string;
  email: string;
}

/* ------------------------------------------------------ API calls */

/**
 * `POST /api/auth/signup` — 광고주 가입.
 * 토큰을 발급하지 않으므로 호출자는 보통 가입 성공 직후 [login] 을 한 번 더
 * 호출해 자동 로그인 시킨다.
 */
export async function signup(req: SignupRequest): Promise<SignupResponse> {
  return apiFetch<SignupResponse>("/api/auth/signup", {
    method: "POST",
    body: req,
    bearerToken: null,
  });
}

/**
 * `POST /api/auth/login` — JWT 발급. 성공 시 토큰/사용자 정보를 자동 저장.
 */
export async function login(req: LoginRequest): Promise<LoginResponse> {
  const response = await apiFetch<LoginResponse>("/api/auth/login", {
    method: "POST",
    body: req,
    bearerToken: null,
  });
  storeSession(response);
  return response;
}

/** 토큰/신원을 localStorage 에서 비우고 어드민 헤더가 즉시 갱신되도록 알림. */
export function logout(): void {
  if (typeof window === "undefined") return;
  try {
    window.localStorage.removeItem(AUTH_TOKEN_STORAGE_KEY);
    window.localStorage.removeItem(AUTH_USER_STORAGE_KEY);
  } catch {
    /* 시크릿 모드 등에서 접근 실패 — 무시 */
  }
  // 같은 탭의 헤더가 즉시 다시 그려지도록 직접 storage 이벤트를 디스패치.
  // (window.dispatchEvent로 보낸 StorageEvent는 일부 브라우저에서 기본
  // 'storage' 이벤트를 발화하지 않으므로 커스텀 이벤트도 함께 보낸다.)
  window.dispatchEvent(new Event("adsignage:auth-changed"));
}

/* ------------------------------------------------------ storage helpers */

/** 응답을 localStorage에 저장하고 헤더가 다시 그려지도록 이벤트 발화. */
function storeSession(res: LoginResponse): void {
  if (typeof window === "undefined") return;
  try {
    window.localStorage.setItem(AUTH_TOKEN_STORAGE_KEY, res.accessToken);
    const user: StoredAuthUser = {
      advertiserId: res.advertiserId,
      email: res.email,
    };
    window.localStorage.setItem(AUTH_USER_STORAGE_KEY, JSON.stringify(user));
  } catch {
    /* 저장 실패는 치명적이지 않음 — 호출자가 응답으로 페이지 이동만 하면 됨 */
  }
  window.dispatchEvent(new Event("adsignage:auth-changed"));
}

/** 저장된 광고주 신원 읽기. 클라이언트 환경 외에서는 `null`. */
export function readStoredAuthUser(): StoredAuthUser | null {
  if (typeof window === "undefined") return null;
  try {
    const raw = window.localStorage.getItem(AUTH_USER_STORAGE_KEY);
    if (!raw) return null;
    const obj = JSON.parse(raw) as Partial<StoredAuthUser>;
    if (typeof obj?.advertiserId !== "string" || typeof obj?.email !== "string") {
      return null;
    }
    return { advertiserId: obj.advertiserId, email: obj.email };
  } catch {
    return null;
  }
}

/* ------------------------------------------------------ error helpers */

/**
 * `ApiError` (백엔드의 ApiError JSON 구조)에서 사용자에게 보여줄 한 줄
 * 메시지를 뽑아낸다. fieldErrors 가 있으면 그것도 함께 노출.
 */
export function describeAuthError(err: unknown): {
  message: string;
  status: number | null;
  fieldErrors?: Record<string, string>;
} {
  if (err instanceof ApiError) {
    const body = err.body as
      | { message?: string; fieldErrors?: Record<string, string> | null }
      | null
      | undefined;
    return {
      message: body?.message?.trim() || `요청 실패 (HTTP ${err.status})`,
      status: err.status,
      fieldErrors: body?.fieldErrors ?? undefined,
    };
  }
  if (err instanceof Error) {
    return { message: err.message, status: null };
  }
  return { message: "알 수 없는 오류가 발생했습니다.", status: null };
}
