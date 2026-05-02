"use client";

/**
 * usePlayerSse — React hook for the player page's SSE subscription.
 *
 * AC 5, Sub-AC 4 (Player/Android):
 *   "Implement SSE subscription on player page so device reloads schedule
 *    immediately when remapped without manual refresh."
 *
 * What this hook does:
 *   1. Opens a long-lived `EventSource` against
 *        GET /api/devices/{deviceId}/stream
 *      (served by Spring `DeviceStreamController`, which keeps the connection
 *      open with timeout=0 and emits a `CONNECTED` handshake event.)
 *      The legacy `/api/devices/{deviceId}/events` controller is still
 *      mounted backend-side for already-deployed Android WebViews; new
 *      players use `/stream` per AC 1.
 *   2. Listens for the wire events defined in the backend's `SseEventNames`:
 *        - `CONNECTED`        — handshake; we just flip status to "open".
 *        - `MAPPING_CHANGED`  — the device was just remapped to a new
 *                               restaurant; we MUST reload the schedule.
 *        - `PLAYLIST_UPDATE`  — the playlist contents changed (ad added,
 *                               schedule edited); also reload.
 *   3. Calls the consumer-supplied `onScheduleReload(reason)` callback
 *      whenever a MAPPING_CHANGED or PLAYLIST_UPDATE event arrives. The
 *      callback is what actually re-fetches `/api/devices/{id}/playlist`
 *      and swaps the active playlist on the player — without a manual
 *      refresh and without reloading the WebView.
 *   4. Auto-reconnects with exponential backoff if the EventSource errors
 *      out (network blip, nginx restart, server redeploy). The browser's
 *      native EventSource also reconnects on its own using the
 *      `retry:` field the server sends, but it gives up on hard errors —
 *      so we add an explicit reconnect loop on top to make the demo
 *      resilient on flaky restaurant WiFi.
 *   5. Cleans up the EventSource and any pending reconnect timer when the
 *      component unmounts or `deviceId` changes — preventing the leaked
 *      connections that would otherwise pile up on the backend's
 *      `DeviceSseRegistry`.
 *
 * Why this is a hook (not embedded in the page component):
 *   - Keeps the SSE side-effect lifecycle isolated and testable.
 *   - Lets the player page stay declarative (it just receives `status`
 *     and the hook calls `onScheduleReload` when something changed).
 *   - Sibling sub-ACs (round-robin playback, splash screens, video range
 *     player) can compose this hook without re-implementing SSE plumbing.
 *
 * Wire contract reference (kept here verbatim for cross-checking):
 *   event: CONNECTED
 *   data:  { "deviceId": "...", "serverTime": "..." }
 *
 *   event: MAPPING_CHANGED
 *   data:  { "deviceId": "...", "restaurantId": "...",
 *            "assignmentId": "...", "assignedAt": "..." }
 *
 *   event: PLAYLIST_UPDATE
 *   data:  { "deviceId": "...", ... }   // reserved; minimal shape.
 */

import { useEffect, useRef, useState } from "react";
import { apiUrl } from "@/lib/api";

/** Wire event names — must match backend `SseEventNames`. */
export const SSE_EVENT_CONNECTED = "CONNECTED" as const;
export const SSE_EVENT_MAPPING_CHANGED = "MAPPING_CHANGED" as const;
export const SSE_EVENT_PLAYLIST_UPDATE = "PLAYLIST_UPDATE" as const;

/** SSE connection lifecycle for the UI to reflect. */
export type SsePlayerStatus =
  | "idle" // hook not yet connected (e.g. deviceId blank)
  | "connecting" // EventSource created, no handshake yet
  | "open" // CONNECTED received OR readyState === OPEN
  | "reconnecting" // disconnected, waiting to retry
  | "error"; // EventSource not supported or fatal failure

/** Reason the consumer's `onScheduleReload` was invoked. */
export type ReloadReason =
  /**
   * Page mount — the player route just rendered. AC 7, Sub-AC 1 requires
   * an initial playlist fetch on mount that does NOT wait for SSE. This
   * reason is supplied by the player page's own mount effect (not by
   * this hook) so the same `onScheduleReload` callback handles every
   * fetch trigger. Listed here so the type stays exhaustive and tooling
   * (switch/case, exhaustive-deps) covers it everywhere.
   */
  | { kind: "initial" }
  | { kind: "mapping_changed"; restaurantId: string; assignmentId: string }
  /**
   * AC 60201, Sub-AC 1: PLAYLIST_UPDATE was received and parsed. The
   * tolerant `payload` carries whatever the backend sent (currently
   * `{ deviceId, ... }`, with an optional inline `playlist` reserved for
   * forward-compat) so the consumer's `setState`/reducer can choose to:
   *   - apply the inline playlist directly (fast path, no refetch), OR
   *   - fall back to refetching `/api/devices/{id}/playlist`.
   * `payload` is null only if the SSE `data:` line was missing or
   * unparseable; in that case the consumer should refetch.
   */
  | { kind: "playlist_update"; payload: PlaylistUpdatePayload | null }
  /**
   * Initial connect — fired exactly once after the first successful
   * `CONNECTED` handshake. Acts as a belt-and-braces refetch in addition
   * to the page's mount-time fetch, useful if the mount fetch fails and
   * the SSE channel later confirms the backend is healthy.
   */
  | { kind: "connected" };

/**
 * Decoded `MAPPING_CHANGED` payload. Mirrors backend `MappingChangedPayload`
 * (Kotlin) — see backend/src/main/kotlin/me/owldev/adsignage/sse/SseEvents.kt.
 */
export interface MappingChangedPayload {
  deviceId: string;
  restaurantId: string;
  assignmentId: string;
  assignedAt: string;
}

/**
 * Decoded `PLAYLIST_UPDATE` payload (AC 60201 Sub-AC 1).
 *
 * The backend's wire contract for this event is documented as "reserved;
 * minimal shape" — at minimum it carries `deviceId` so the player can
 * confirm the event is targeted at this connection (defence in depth: the
 * SSE emitter is per-device, but a shared channel at the proxy layer could
 * still mis-deliver).
 *
 * Forward-compat optional fields:
 *   - `playlist`     → inline new playlist; if present the player can apply
 *                      it directly via `setPlaylistState` without a refetch.
 *   - `updatedAt`    → ISO-8601 timestamp of when the schedule changed; lets
 *                      the consumer ignore an out-of-order event.
 *   - `restaurantId` → echoed for correlation only; not required.
 *
 * Inline `playlist` mirrors `DevicePlaylist` from `lib/playlist.ts` but is
 * intentionally typed as `unknown` here so the hook stays decoupled from
 * the playlist module — the consumer (PlayerClient) re-uses the existing
 * playlist normaliser to validate the shape before committing it to state.
 */
export interface PlaylistUpdatePayload {
  deviceId: string;
  restaurantId?: string | null;
  playlist?: InlinePlaylist | null;
  updatedAt?: string | null;
}

/**
 * Loose inline-playlist shape carried by a `PLAYLIST_UPDATE` event. Mirrors
 * the JSON returned by `GET /api/devices/{id}/playlist` so the consumer can
 * pass it through the same normaliser used for the refetch path. Kept
 * `unknown`-keyed so unexpected fields don't fail parsing — the normaliser
 * downstream is responsible for trimming to the canonical `DevicePlaylist`.
 */
export interface InlinePlaylist {
  deviceId?: string;
  restaurantId?: string | null;
  ads?: unknown[] | null;
  items?: unknown[] | null;
  fetchedAt?: string;
}

export interface UsePlayerSseOptions {
  /** Required device UUID. The hook is a no-op while this is empty. */
  deviceId: string;
  /**
   * Called when the player should re-fetch its playlist. Fired:
   *   - once on initial connect (so the page renders something on load);
   *   - on every MAPPING_CHANGED;
   *   - on every PLAYLIST_UPDATE.
   * The consumer typically does `await fetchPlaylist(deviceId)` and swaps
   * the `<video>` source — that is the "device reloads schedule
   * immediately when remapped without manual refresh" behaviour.
   */
  onScheduleReload: (reason: ReloadReason) => void;
  /**
   * Optional notification when the device is remapped. Useful for the
   * page to show a "Switching to {restaurant}…" splash without waiting
   * on the playlist round-trip (the backend already includes the new
   * restaurantId in the SSE payload).
   */
  onMappingChanged?: (payload: MappingChangedPayload) => void;
  /**
   * Override the initial reconnect delay (ms). The hook backs off
   * exponentially from this value up to [maxReconnectDelayMs].
   * Default 1000 ms.
   */
  initialReconnectDelayMs?: number;
  /** Cap for the exponential backoff. Default 30_000 ms. */
  maxReconnectDelayMs?: number;
  /**
   * Enable verbose console logging (helpful during the live demo so the
   * operator can watch the WebView console and see remap events arrive).
   * Default false.
   */
  debug?: boolean;
}

export interface UsePlayerSseResult {
  /** Current SSE lifecycle status — drive a status pill off this. */
  status: SsePlayerStatus;
  /**
   * Monotonically-increasing counter, bumped each time the consumer's
   * `onScheduleReload` is invoked. Useful as a `useEffect` dependency
   * for callers that prefer a declarative refetch pattern over the
   * imperative callback (e.g. `useEffect(() => fetchPlaylist(), [reloadCounter])`).
   */
  reloadCounter: number;
  /** Number of automatic reconnect attempts since the last success. */
  reconnectAttempts: number;
  /** Time the last `MAPPING_CHANGED` event was received (ms epoch), or 0. */
  lastMappingChangeAt: number;
  /**
   * Imperative trigger to drop the current connection and reopen — handy
   * for a "Reconnect now" debug button on the player splash screen.
   */
  reconnectNow: () => void;
}

/**
 * Subscribes the player page to the backend SSE stream for [deviceId]
 * and invokes `onScheduleReload` whenever the schedule should be
 * re-fetched.
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

  // Latest callbacks held in refs so we can keep the EventSource open
  // across renders without re-subscribing every time the consumer's
  // function identity changes.
  const onScheduleReloadRef = useRef(onScheduleReload);
  const onMappingChangedRef = useRef(onMappingChanged);
  useEffect(() => {
    onScheduleReloadRef.current = onScheduleReload;
  }, [onScheduleReload]);
  useEffect(() => {
    onMappingChangedRef.current = onMappingChanged;
  }, [onMappingChanged]);

  // Manual reconnect signal — bumped to force the connect-effect to re-run.
  const [reconnectNonce, setReconnectNonce] = useState<number>(0);
  const reconnectNow = () => setReconnectNonce((n) => n + 1);

  useEffect(() => {
    if (!deviceId) {
      setStatus("idle");
      return;
    }

    if (typeof window === "undefined" || typeof EventSource === "undefined") {
      // SSR or a runtime without EventSource (extremely old WebView).
      // The hackathon WebView (Android System WebView) supports it, so this
      // is mostly a safety net for unit tests / build-time prerender.
      setStatus("error");
      return;
    }

    let cancelled = false;
    let es: EventSource | null = null;
    let reconnectTimer: ReturnType<typeof setTimeout> | null = null;
    let attempts = 0;
    let firstConnectFired = false;

    // AC 1 — subscribe to `/stream`, not `/events`. Backend exposes both
    // routes (DeviceStreamController + legacy DeviceSseController) and the
    // wire contract — CONNECTED handshake, MAPPING_CHANGED, PLAYLIST_UPDATE
    // — is identical, so flipping the URL here is a one-line drop-in.
    const url = apiUrl(`/api/devices/${encodeURIComponent(deviceId)}/stream`);

    const log = (...args: unknown[]) => {
      if (debug) console.log("[usePlayerSse]", ...args);
    };

    const triggerReload = (reason: ReloadReason) => {
      setReloadCounter((n) => n + 1);
      try {
        onScheduleReloadRef.current(reason);
      } catch (err) {
        // Never let a consumer exception kill the SSE pipeline.
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

        // The browser's onopen fires on TCP/HTTP-level open. We treat the
        // CONNECTED event as the canonical "channel is healthy" signal,
        // because it confirms the server-side handler ran. But we still
        // listen to onopen so the status flips out of "connecting" even
        // if the server is slow to send the handshake.
        es.onopen = () => {
          log("EventSource opened");
          // Don't reset attempts here — wait for first event (CONNECTED) so
          // we know the *server* (not just the proxy) is alive.
        };

        es.onerror = (ev) => {
          // EventSource will auto-reconnect on its own for transient errors
          // (when readyState === CONNECTING). For CLOSED or other terminal
          // states, we need to take over.
          log("EventSource error", ev, "readyState=", es?.readyState);
          if (!es) return;
          if (es.readyState === EventSource.CLOSED) {
            es.close();
            es = null;
            scheduleReconnect();
          }
          // For CONNECTING (1) we let the browser try once; if it keeps
          // failing it will eventually transition to CLOSED and the branch
          // above takes over.
        };

        // CONNECTED — handshake. Backend always sends this immediately on
        // a new emitter. Use it to flip status -> open and to fire the
        // initial schedule reload so the player paints something on first
        // paint without a separate effect.
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

        // MAPPING_CHANGED — THE event sub-AC 4 exists for. Decode the
        // payload, hand it to the optional onMappingChanged hook (so the
        // page can paint a transition splash with the new restaurantId
        // straight away), then trigger a full schedule reload via the
        // primary callback.
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

        // PLAYLIST_UPDATE — schedule contents changed for any other reason
        // (advertiser added a new ad, edited a schedule, etc.). AC 60201
        // Sub-AC 1: parse the incoming `data:` JSON and pass the typed
        // payload to the consumer so its setState/reducer can either apply
        // an inline playlist directly OR refetch authoritatively.
        es.addEventListener(SSE_EVENT_PLAYLIST_UPDATE, (e: MessageEvent) => {
          log("PLAYLIST_UPDATE received", e.data);
          const payload = parsePlaylistUpdatePayload(e.data);
          // Defence in depth: if the SSE event was somehow delivered for a
          // different device (mis-routing at a proxy, registry bug, etc.)
          // log a warning but still trigger a reload — the refetch path
          // hits the device-scoped `/api/devices/{thisDeviceId}/playlist`
          // endpoint, so the wrong-device data can't leak into state.
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
    // `reconnectNonce` lets `reconnectNow()` re-run this effect to drop
    // and reopen the EventSource. The other deps are configuration that
    // legitimately invalidates the open connection if they change.
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
 * Parse the JSON body of a MAPPING_CHANGED SSE event. Tolerant: returns
 * null if the payload is malformed so the caller can still trigger a
 * schedule reload (the reload will fetch the authoritative state from
 * the backend anyway).
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
 * Parse the JSON body of a PLAYLIST_UPDATE SSE event (AC 60201 Sub-AC 1).
 *
 * Tolerant by design — the wire contract for PLAYLIST_UPDATE is documented
 * as "reserved; minimal shape" and may evolve as sibling sub-ACs add new
 * fields (timestamps, inline playlists, change reasons). The parser:
 *
 *   - Returns `null` if the body is empty or not valid JSON. The caller
 *     should still trigger a refetch — the backend is the source of
 *     truth, and SSE is best-effort signalling.
 *
 *   - Returns a typed object with at least `deviceId` if it could be
 *     extracted. Optional fields are passed through unchanged so future
 *     backend versions can add fields without a coordinated client roll.
 *
 *   - For an inline `playlist`, only validates that it's an object — the
 *     consumer should pipe it through `lib/playlist.ts#normalisePlaylist`
 *     before committing to React state, so unknown extra keys are
 *     trimmed and missing fields default sanely.
 *
 * @param raw  The `data:` line from the SSE event (already utf-8 decoded
 *             by EventSource, but still a string of JSON).
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
 * Internal helpers exposed for unit testing. Not part of the public hook
 * API — consumers should not depend on these. Re-exported here (rather
 * than via a separate `__tests__` file) because the project does not yet
 * have a JS test runner configured; this keeps the parsers reachable for
 * a future jest/vitest suite without restructuring the hook.
 */
export const __test__ = {
  parseMappingChangedPayload,
  parsePlaylistUpdatePayload,
};
