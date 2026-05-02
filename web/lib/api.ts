/**
 * Tiny API client for the AdSignage admin web.
 *
 * All HTTP traffic from the admin UI flows through this module so that:
 *   - the API base URL is centralised (NEXT_PUBLIC_API_BASE_URL),
 *   - JSON / error handling is uniform,
 *   - JWT auth (Bearer) is auto-attached on browser calls to protected
 *     endpoints (Spring `SecurityConfig` requires it for `/api/ads/**`),
 *   - calls work both same-origin (behind nginx on stream.owl-dev.me) and
 *     cross-origin during local dev (e.g. http://192.168.0.24:8080).
 *
 * Server Components can call these helpers directly thanks to Next.js's built-in
 * `fetch` polyfill. Each request is marked `cache: 'no-store'` because the
 * admin views need live operator data, not the Next data cache.
 */

const RAW_BASE_URL = process.env.NEXT_PUBLIC_API_BASE_URL ?? "";

/**
 * localStorage key under which the admin web persists the advertiser JWT after
 * login. Mirrored across the codebase (`web/lib/videos.ts`, the auth UI doc
 * comments, and the form copy) so a single key keeps "logged in" state.
 */
export const AUTH_TOKEN_STORAGE_KEY = "adsignage_auth_token";

/**
 * Read the persisted JWT from `localStorage`. Returns `null` server-side
 * (Server Components, SSR fetches) and in environments where storage is
 * unavailable (private browsing throws on access in some browsers).
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
 * Returns the configured backend base URL with any trailing slash trimmed,
 * or "" to mean "same-origin" (i.e. requests like `/api/devices`).
 */
export function getApiBaseUrl(): string {
  return RAW_BASE_URL.replace(/\/+$/, "");
}

/**
 * Joins the API base URL with a request path. The path may or may not have a
 * leading slash; the result always has exactly one separator.
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
  /** Force-disable Next's data cache; defaults to true for live operator data. */
  noStore?: boolean;
  /**
   * Explicit JWT to send as `Authorization: Bearer <token>`. Overrides the
   * `localStorage` fallback. Pass `null` to opt out of auth attachment for
   * this single call (e.g. public player APIs that must NOT carry a token).
   */
  bearerToken?: string | null;
};

/**
 * Low-level helper: performs a JSON request against the backend and throws
 * [ApiError] on non-2xx responses. Use the typed wrappers below for callers.
 *
 * Auth attachment policy:
 *   - If the caller passes `bearerToken`, that value wins (`null` opts out).
 *   - Otherwise, in the browser, fall back to
 *     `localStorage.adsignage_auth_token` so authenticated admin endpoints
 *     (e.g. `PUT /api/ads/{id}/schedule`) just work after login.
 *   - Server-side (Server Components / SSR) there is no `localStorage`, so
 *     nothing is attached unless the caller is explicit.
 *   - A pre-set `Authorization` header in `init.headers` always wins over
 *     either of the above.
 */
export async function apiFetch<T>(path: string, init: ApiFetchInit = {}): Promise<T> {
  const url = apiUrl(path);
  const { body, headers, noStore = true, bearerToken, ...rest } = init;

  const finalHeaders: Record<string, string> = {
    Accept: "application/json",
    ...(body !== undefined ? { "Content-Type": "application/json" } : {}),
    ...((headers as Record<string, string> | undefined) ?? {}),
  };

  // Attach JWT only when the caller hasn't already pinned an Authorization
  // header. `bearerToken === null` is a deliberate opt-out (public endpoints).
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

  // 204 No Content / empty body -> return undefined as T
  if (response.status === 204) return undefined as T;
  const text = await response.text();
  if (!text) return undefined as T;
  return JSON.parse(text) as T;
}
