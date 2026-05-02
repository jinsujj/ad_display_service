/**
 * Player → backend play-event reporting (AC 20202 Sub-AC 2).
 *
 * Owns the client side of the wire contract introduced by the Spring
 * `PlayEventController`:
 *
 *   POST /api/devices/{deviceId}/play-events
 *     Content-Type: application/json
 *     {
 *       "adId":       "<uuid>",
 *       "eventType":  "STARTED" | "FINISHED",
 *       "occurredAt": "<ISO-8601>"             // optional
 *     }
 *
 *     ⇒ 201 Created on success (the player intentionally ignores the body).
 *
 * Why a dedicated module rather than calling `apiFetch` inline from the
 * player React component:
 *
 *  - **Hot path resilience.** The endpoint is fire-and-forget telemetry;
 *    the screen must keep playing the next ad regardless of whether the
 *    backend POST succeeds. Centralising the call here means we can write
 *    *one* swallow-and-log error handler instead of repeating it at every
 *    `<video onPlay>` / `<video onEnded>` callsite — and a future change
 *    (retry-with-backoff, beacon-on-unload) lands in one place.
 *
 *  - **Auth boundary.** The player APIs are unauthenticated by design (see
 *    `SecurityConfig`'s allow-list of `/api/devices/*\/play-events`). The
 *    shared `apiFetch` defaults to attaching a `localStorage` JWT when the
 *    admin web is open in the same browser profile — that would be a
 *    semantic leak (the player must NOT carry an advertiser token).
 *    Passing `bearerToken: null` opts out of auth attachment cleanly.
 *
 *  - **Testability.** A pure function with a small interface is trivial to
 *    cover under `node --test`, matching the vendored-impl pattern used by
 *    sibling player libraries (`roundRobin.test.mjs`, `dailyCount.test.mjs`).
 *
 *  - **SSR safety.** Reused on both the player page (browser-only) and
 *    potential server-component prerenders. The `fetch` is always a no-op
 *    on the server because the player route is a client component, but
 *    structuring the call here keeps the contract obvious.
 */

import { apiFetch, ApiError } from "./api";

/**
 * The two play-event signals the player can report. Mirrors
 * `me.owldev.adsignage.domain.playevent.PlayEventType` on the backend so
 * the wire shape stays JSON-isomorphic with the JPA enum.
 */
export type PlayEventType = "STARTED" | "FINISHED";

/** Request body for POST /api/devices/{deviceId}/play-events. */
export interface PlayEventRequest {
  adId: string;
  eventType: PlayEventType;
  /**
   * ISO-8601 instant for when the event happened on the device. The
   * backend treats this as optional and falls back to its own clock; the
   * helper still sends one by default so a clock-skew analysis pass has
   * matched pairs to compare.
   */
  occurredAt?: string;
}

/**
 * Server response shape (echo of the persisted row). The player ignores
 * this for the hot path; exported so a future debug overlay can show the
 * server-stamped `receivedAt` if needed.
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
 * POSTs a single play event to the backend.
 *
 * Behaviour contract:
 *  - Returns the persisted [PlayEventResponse] on 2xx. Throws an
 *    [ApiError] (re-exported from `./api`) on non-2xx responses, so callers
 *    that *do* care about failures can catch them — but the recommended
 *    pattern for the player is [reportPlayEvent], which logs-and-swallows.
 *  - `bearerToken: null` — explicit auth opt-out. Player traffic must not
 *    carry an advertiser JWT even when the same browser has a token in
 *    localStorage from a sibling admin tab.
 *  - `noStore: true` — telemetry POSTs are obviously uncached, but flipping
 *    the flag also tells Next.js (Server Components / Route Handlers) not
 *    to memoise the response, matching the rest of `apiFetch`.
 *
 * Pure / no React deps so the unit-test mirror can exercise it without a
 * DOM. The actual `<video>` wiring lives in `PlayerClient.tsx`.
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
    // Default to the current device wall-clock so the server has a
    // matched pair (`occurredAt` from the device, `receivedAt` stamped on
    // arrival). A future debug overlay can subtract them to surface
    // clock skew, and operators tailing the server log get the device's
    // own timestamp without having to enable the optional field on every
    // client.
    occurredAt: body.occurredAt ?? new Date().toISOString(),
  };

  return apiFetch<PlayEventResponse>(
    `/api/devices/${encodeURIComponent(deviceId)}/play-events`,
    {
      method: "POST",
      body: payload,
      // Player is anonymous — never attach a stored advertiser JWT.
      bearerToken: null,
    },
  );
}

/**
 * Fire-and-forget wrapper around [postPlayEvent] for the player's hot
 * path. Used by `<video onPlay>` and `<video onEnded>` — operator
 * expectation is that the screen keeps playing the next ad even if the
 * telemetry POST fails (network blip, backend redeploying, transient
 * 5xx). Any error is logged at warn level and swallowed.
 *
 * Returns a Promise that always resolves; awaiting it is optional and
 * exists only so callers can chain log statements during development.
 *
 * Why a separate function rather than always-swallow inside [postPlayEvent]:
 * a future analytics dashboard or a retry queue may want the typed
 * `ApiError` to make decisions on. Keeping the throwing contract on the
 * low-level helper preserves that option without forcing the player code
 * to wrap a try/catch around every `<video>` callback.
 */
export async function reportPlayEvent(
  deviceId: string,
  body: PlayEventRequest,
): Promise<PlayEventResponse | null> {
  try {
    return await postPlayEvent(deviceId, body);
  } catch (err) {
    // Log enough to triage from the WebView devtools without stealing
    // attention from real failures. ApiError carries the URL + status;
    // anything else is rethrown as an unstructured Error.
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

/** Re-export so the player page can import everything from one module. */
export { ApiError };
