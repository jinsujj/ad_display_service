/**
 * Player playlist API surface.
 *
 * Wire contract (Spring Boot backend, owned by sibling sub-ACs):
 *
 *   GET /api/devices/{id}/playlist
 *     -> 200 OK, application/json
 *        {
 *          "deviceId":     string,
 *          "restaurantId": string?,                  // null if unmapped
 *          "ads": [
 *            {
 *              "adId":         string,
 *              "title":        string,
 *              "videoUrl":     string,               // absolute or path
 *              "scheduleId":   string,
 *              "startTime":    string?,              // "HH:mm"
 *              "endTime":      string?,              // "HH:mm"
 *              "dailyCount":   number?
 *            },
 *            ...
 *          ],
 *          "fetchedAt": string                       // ISO-8601
 *        }
 *
 * The endpoint is allow-listed in `SecurityConfig` (see comments on the
 * `/api/devices/*\/playlist` matcher) and is being implemented in a
 * sibling sub-AC. This module is tolerant of the endpoint not yet
 * existing — callers (the player page) catch the [ApiError] and render a
 * splash so the SSE-driven reload still has somewhere to land safely.
 *
 * Why a separate file (vs. extending `lib/devices.ts`):
 *   The devices module is admin-side; the playlist is player-side. They
 *   evolve independently and the player should not pull in admin-only
 *   types (and vice versa).
 */

import { apiFetch, apiUrl, ApiError } from "./api";

/** A single ad entry in the device's current playlist. */
export interface PlaylistAd {
  adId: string;
  title: string;
  /**
   * Resolved absolute URL the `<video>` element can `src` directly.
   * If the backend returns a relative path, [fetchPlaylist] resolves it
   * against the API base URL so the WebView can play it without further
   * processing.
   */
  videoUrl: string;
  scheduleId: string;
  startTime?: string | null;
  endTime?: string | null;
  dailyCount?: number | null;
}

/** Full playlist payload for one device. */
export interface DevicePlaylist {
  deviceId: string;
  restaurantId: string | null;
  ads: PlaylistAd[];
  fetchedAt: string;
}

type RawPlaylistAd = Partial<PlaylistAd> & {
  id?: string;
  url?: string;
  src?: string;
};

type RawPlaylist = {
  deviceId?: string;
  restaurantId?: string | null;
  ads?: RawPlaylistAd[] | null;
  items?: RawPlaylistAd[] | null;
  fetchedAt?: string;
};

/**
 * Fetches the current playlist for [deviceId]. Re-runs after every SSE
 * MAPPING_CHANGED / PLAYLIST_UPDATE event from the player page (see
 * `hooks/usePlayerSse.ts`).
 *
 * The result is normalised so the player can treat the playlist
 * uniformly regardless of small backend shape variations during the
 * hackathon bring-up.
 */
export async function fetchPlaylist(
  deviceId: string,
): Promise<DevicePlaylist> {
  if (!deviceId) throw new Error("deviceId is required");
  const raw = await apiFetch<RawPlaylist>(
    `/api/devices/${encodeURIComponent(deviceId)}/playlist`,
  );
  return normalisePlaylist(deviceId, raw);
}

function normalisePlaylist(
  deviceId: string,
  raw: RawPlaylist | null | undefined,
): DevicePlaylist {
  const adsSource = Array.isArray(raw?.ads)
    ? raw!.ads!
    : Array.isArray(raw?.items)
      ? raw!.items!
      : [];
  const ads = adsSource
    .map(normaliseAd)
    .filter((a): a is PlaylistAd => a !== null);
  return {
    deviceId: raw?.deviceId ?? deviceId,
    restaurantId: raw?.restaurantId ?? null,
    ads,
    fetchedAt: raw?.fetchedAt ?? new Date().toISOString(),
  };
}

/**
 * Public-API counterpart of [normalisePlaylist] for the SSE fast-path
 * (AC 60201 Sub-AC 1).
 *
 * When a `PLAYLIST_UPDATE` SSE event carries an inline `playlist` on the
 * wire, the player hook hands that loosely-typed object to this function
 * to coerce it into a canonical `DevicePlaylist` before committing to
 * React state. Re-uses the exact normalisation rules as the HTTP refetch
 * path so playback is identical regardless of which channel delivered the
 * playlist.
 *
 * Throws if the input is not a plain object — the caller (PlayerClient)
 * catches and falls back to a refetch in that case.
 */
export function normaliseInlinePlaylist(
  deviceId: string,
  raw: unknown,
): DevicePlaylist {
  if (!raw || typeof raw !== "object") {
    throw new Error("inline playlist must be a JSON object");
  }
  return normalisePlaylist(deviceId, raw as RawPlaylist);
}

function normaliseAd(raw: RawPlaylistAd): PlaylistAd | null {
  const adId = raw.adId ?? raw.id ?? "";
  if (!adId) return null;
  const rawUrl = raw.videoUrl ?? raw.url ?? raw.src ?? "";
  if (!rawUrl) return null;
  return {
    adId,
    title: raw.title ?? "",
    videoUrl: resolveVideoUrl(rawUrl),
    scheduleId: raw.scheduleId ?? "",
    startTime: raw.startTime ?? null,
    endTime: raw.endTime ?? null,
    dailyCount: raw.dailyCount ?? null,
  };
}

/**
 * Make sure the `<video src>` is something the WebView can resolve.
 *
 * Cases:
 *   - "https://..." / "http://..." → returned unchanged.
 *   - "/api/videos/abc.mp4"        → joined with the API base URL.
 *   - "videos/abc.mp4"             → joined with API base URL + leading "/".
 *
 * Resolution is needed because the player runs at the Next.js origin
 * (e.g. https://stream.owl-dev.me), but the videos are served from the
 * Spring backend (which may be at a different host during local dev).
 */
function resolveVideoUrl(url: string): string {
  if (/^https?:\/\//i.test(url)) return url;
  return apiUrl(url);
}

/* ---------------------------------------------------------- AC 9 helpers
 *
 * AC 9 — "Ads play only within scheduled time window".
 *
 * The wire contract above already carries `startTime` / `endTime` per ad
 * (HH:mm strings, device-local wall-clock semantics — see backend
 * `Ad.kt` / `AdScheduleDtos.kt`). The backend's CHECK constraint
 * `ck_ads_time_window` guarantees `endTime > startTime` so we can treat
 * the window as a simple half-open interval `[start, end)` without having
 * to model midnight wraparound.
 *
 * The player is the place this rule is enforced — per the seed:
 *   "All playback/schedule/SSE logic lives in Next.js player page,
 *    not native Android."
 *
 * Two pure helpers keep the rule testable in isolation from the React
 * component, and let the round-robin AC keep using the same playlist
 * shape it already iterates over (we just hand it a narrowed list).
 */

/**
 * Parse an `HH:mm` clock string into a minutes-since-midnight integer.
 *
 * Returns `null` on any malformed input — that includes the empty
 * string, missing field, out-of-range hour/minute, or non-numeric
 * fragments. Callers (see [isAdActive]) treat a `null` result as
 * "no schedule on this end of the window" and conservatively allow
 * playback rather than masking an ad permanently because of a server
 * formatting bug.
 *
 * Exported for the player's status overlay so it can render the
 * window in a human-readable form alongside the live filter outcome.
 */
export function parseHhMmToMinutes(value: string | null | undefined): number | null {
  if (!value) return null;
  // Accept "HH:mm" only — backend serialises with this exact pattern.
  // Reject "H:mm", "HHmm", "HH:mm:ss", or trailing whitespace so we
  // never silently accept a mis-formatted window.
  const match = /^([0-9]{2}):([0-9]{2})$/.exec(value);
  if (!match) return null;
  const h = Number(match[1]);
  const m = Number(match[2]);
  if (!Number.isFinite(h) || !Number.isFinite(m)) return null;
  if (h < 0 || h > 23) return null;
  if (m < 0 || m > 59) return null;
  return h * 60 + m;
}

/**
 * Convert a [Date] to the same minutes-since-midnight integer used by
 * [parseHhMmToMinutes], in the device's local timezone (the timezone
 * of the JS runtime — which on the WebView is the device timezone).
 *
 * Done this way (vs. `toLocaleTimeString` parsing) because:
 *   - it never allocates a string, so it is cheap to call on every
 *     render of the player while the wall-clock tick is live;
 *   - it is locale-independent, so the player behaves identically on
 *     a Korean and an English Android device;
 *   - it sidesteps the half-hour timezones (e.g. Asia/Kabul) that
 *     can break naive `toISOString().slice(11, 16)` approaches.
 */
export function dateToLocalMinutes(date: Date): number {
  return date.getHours() * 60 + date.getMinutes();
}

/**
 * AC 9 predicate — is [ad] currently within its scheduled play window?
 *
 * Semantics:
 *   - Window is `[startTime, endTime)` — start is inclusive, end is
 *     exclusive. Matches the typical "advertise during dinner service
 *     17:00..21:00" intent: at 21:00 the window has just ended.
 *   - Wall-clock time is taken from [now] in the device's local
 *     timezone. Defaults to a fresh `new Date()` so callers can omit
 *     it for the simple case while tests can inject a fixed time.
 *   - If either bound is missing or unparseable, that side of the
 *     window is treated as unconstrained. Two missing bounds means
 *     "always active" — fail-open so a server bug producing nulls
 *     doesn't blackhole a customer's entire schedule. The backend's
 *     own validation (`UpdateAdScheduleRequest`) guarantees both
 *     fields are present in practice.
 *
 * Pure / no React dependencies so it can be exercised by a future jest
 * suite alongside [filterActiveAds].
 */
export function isAdActive(ad: PlaylistAd, now: Date = new Date()): boolean {
  const startMin = parseHhMmToMinutes(ad.startTime);
  const endMin = parseHhMmToMinutes(ad.endTime);
  if (startMin === null && endMin === null) return true;
  const nowMin = dateToLocalMinutes(now);
  if (startMin !== null && nowMin < startMin) return false;
  if (endMin !== null && nowMin >= endMin) return false;
  return true;
}

/**
 * AC 9 — narrow a playlist's `ads` list to only those currently within
 * their scheduled window. Order is preserved so the round-robin AC can
 * continue iterating in playlist order; the active-set is just a stable
 * view over the source array filtered by the live wall clock.
 *
 * Why a separate function rather than a one-liner inline filter on the
 * caller side:
 *   - Lets the player page surface "active n of m" in the status
 *     overlay using the same source of truth as the playback path.
 *   - Keeps the predicate's `now` injection point in one place — easy
 *     to swap to a different time source (e.g. server time) without
 *     hunting through the player component.
 */
export function filterActiveAds(
  ads: PlaylistAd[],
  now: Date = new Date(),
): PlaylistAd[] {
  return ads.filter((ad) => isAdActive(ad, now));
}

/** Re-export for convenience so the player page only imports from one module. */
export { ApiError };
