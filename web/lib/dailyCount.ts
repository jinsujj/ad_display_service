/**
 * Per-ad daily play counter (AC 3, Sub-AC 2).
 *
 * Owns the player-side daily-play-count tracking and cap enforcement that
 * rolls over at local midnight:
 *
 *   "Implement daily_play_count tracking and cap enforcement in the player
 *    (persist per-ad daily counters with date rollover) to stop playback
 *    once limit is reached."
 *
 * ## Why it lives in its own module
 *
 * `PlayerClient.tsx` already wires SSE / playlist state / round-robin /
 * schedule-window filtering. Mixing the counter persistence with that I/O
 * makes both the React effects and the storage logic harder to audit. The
 * counter algorithm is pure arithmetic plus a tiny localStorage layer —
 * extracting it to a side-effect-free module:
 *
 *   - centralises the cap-enforcement contract in ONE place,
 *   - allows the helpers to be unit-tested under `node --test` without a
 *     DOM (we follow the same vendored-impl pattern used by
 *     `roundRobin.test.mjs` and `playlist.schedule.test.mjs`),
 *   - keeps the React surface area in the player file focused on the
 *     "select which ad plays next" semantics rather than persistence.
 *
 * ## Storage shape
 *
 * One localStorage key per device, scoped so two devices on the same
 * Android (rare in practice but possible during dev with multiple WebViews
 * pointing at the same browser profile) cannot clobber each other:
 *
 *   localStorage["adsignage:dailyCount:<deviceId>"] =
 *     JSON.stringify({
 *       date:   "YYYY-MM-DD",        // device-local wall-clock day
 *       counts: { "<adId>": number, ... }
 *     })
 *
 * The wrapper object ALWAYS carries the date the counts were accumulated
 * against. On every load (and on every increment) we compare against
 * today's local date and reset the bag to `{}` if they differ. That is
 * the entire date-rollover mechanism — no timers, no midnight wake-up
 * job, no risk of a sleeping WebView missing the rollover.
 *
 * ## Why local date (not UTC)
 *
 * The daily window the operator configured (`startTime`/`endTime`,
 * "HH:mm") is wall-clock — see `playlist.ts: parseHhMmToMinutes`. A
 * Korean restaurant operator setting "dinner 17:00–21:00, 200 plays/day"
 * expects the 200 to reset at *Korean midnight*, not 09:00 KST (UTC
 * midnight). Reading `getFullYear/getMonth/getDate` gives us the device
 * timezone the user is sitting in, matching the predicate's semantics.
 *
 * ## Cap semantics
 *
 *   - `cap == null`/`undefined`  → unlimited; never filter out.
 *   - `cap <= 0`                 → never play; filter out immediately.
 *   - `cap >= 1`                 → play at most `cap` times today; filter
 *                                  out once `count >= cap`.
 *
 * The check is `count >= cap` (not `> cap`) so the Nth play is allowed
 * and the (N+1)th is suppressed. Increments happen exactly once per
 * completed playthrough (the player's `<video onEnded>` handler).
 *
 * ## SSR / non-browser safety
 *
 * Next.js prerenders the player route on the server during build. There
 * is no `localStorage` in Node — every access guards on `typeof window`
 * and falls back to in-memory state. A failed JSON parse (corrupted
 * storage from an older version) collapses to a fresh empty bag rather
 * than throwing, so a dev with stale data in their browser cannot brick
 * the player.
 */

import type { PlaylistAd } from "./playlist";

/* ------------------------------------------------------------------- types */

/**
 * Persisted shape, also the runtime shape used inside the player. Date
 * carried alongside the counts so the rollover check is a single
 * comparison rather than a separate piece of state to keep in sync.
 */
export interface DailyCounters {
  /**
   * Local-timezone calendar day the [counts] were accumulated against.
   * Format: `YYYY-MM-DD`. Compared with [todayKey] on every read/write
   * to detect a midnight rollover.
   */
  date: string;
  /**
   * Plays-so-far-today, keyed by ad id. Missing keys are treated as `0`
   * — we don't preallocate slots for ads we've never played.
   */
  counts: Record<string, number>;
}

/* ------------------------------------------------------------ date helpers */

/**
 * Pad a 1- or 2-digit integer to two characters. Hand-rolled rather than
 * `String.padStart` so the helper is friendly to vendored-impl test
 * mirrors (which target older JS engines).
 */
function pad2(n: number): string {
  if (n < 10) return "0" + String(n);
  return String(n);
}

/**
 * Format [date] as a local-calendar `YYYY-MM-DD` string. Local — i.e.
 * the device's `getFullYear/getMonth/getDate` — so the rollover happens
 * at the operator's local midnight, matching the wall-clock semantics
 * already in use by [parseHhMmToMinutes] / [isAdActive].
 *
 * Exported so the unit-test mirror and the React status overlay can
 * read the same canonical string.
 */
export function localDateKey(date: Date = new Date()): string {
  const y = date.getFullYear();
  const m = pad2(date.getMonth() + 1); // getMonth is 0-indexed
  const d = pad2(date.getDate());
  return y + "-" + m + "-" + d;
}

/** Today, in local-calendar form, in one place so callers don't drift. */
export function todayKey(): string {
  return localDateKey(new Date());
}

/* ----------------------------------------------------------- cap helpers */

/**
 * Plays-so-far for [adId] given a counters bag. Missing key → 0. Not
 * exported as standalone because every caller wants the cap evaluation
 * too — see [getRemaining] / [isAdCapped] / [filterUnderCap].
 */
function playsForAd(counts: Record<string, number>, adId: string): number {
  const v = counts[adId];
  if (typeof v !== "number" || !Number.isFinite(v) || v < 0) return 0;
  return Math.floor(v);
}

/**
 * How many more times [ad] is allowed to play today, given the current
 * counters. Returns:
 *
 *   - `null`              for an unlimited ad (cap missing/null).
 *   - `0`                 for a capped-out ad (also for `cap <= 0`).
 *   - a positive integer  for an ad with remaining plays.
 *
 * The `null` sentinel is intentional — `Infinity` would silently
 * survive arithmetic that subtracts it (e.g. UI overlays computing
 * `remaining - 1`) and the operator surface needs to render
 * "unlimited" as a distinct state, not as "∞". Pure helper.
 */
export function getRemaining(
  ad: Pick<PlaylistAd, "adId" | "dailyCount">,
  counts: Record<string, number>,
): number | null {
  const cap = normaliseCap(ad.dailyCount);
  if (cap === null) return null;
  const used = playsForAd(counts, ad.adId);
  const remaining = cap - used;
  return remaining > 0 ? remaining : 0;
}

/**
 * Has [ad] hit its daily cap? Inverse of "has remaining plays". An
 * unlimited ad (`cap == null`) is never capped. A `cap <= 0` ad is
 * capped immediately on day one — the operator effectively muted it
 * — but we don't crash on that pathological setting.
 */
export function isAdCapped(
  ad: Pick<PlaylistAd, "adId" | "dailyCount">,
  counts: Record<string, number>,
): boolean {
  const r = getRemaining(ad, counts);
  if (r === null) return false; // unlimited
  return r <= 0;
}

/**
 * Narrow [ads] to those that still have remaining plays today. Order
 * preserved so the round-robin AC keeps iterating in playlist order
 * after the cap-filter is applied. Pure / no React.
 */
export function filterUnderCap<T extends Pick<PlaylistAd, "adId" | "dailyCount">>(
  ads: T[],
  counts: Record<string, number>,
): T[] {
  return ads.filter((ad) => !isAdCapped(ad, counts));
}

/**
 * Coerce the `dailyCount` field on the wire into a typed cap.
 *
 *   - missing/null/undefined → null  (unlimited)
 *   - non-integer / NaN      → null  (degrade to unlimited rather than
 *                                     blackhole an ad on a bad payload)
 *   - negative / zero        → 0     (capped immediately)
 *   - positive integer       → that integer
 *
 * Pure helper, exported for the test mirror.
 */
export function normaliseCap(cap: number | null | undefined): number | null {
  if (cap === null || cap === undefined) return null;
  if (typeof cap !== "number" || !Number.isFinite(cap)) return null;
  if (cap <= 0) return 0;
  return Math.floor(cap);
}

/* ----------------------------------------------------------- state helpers */

/**
 * Apply a date-rollover to [state]: if its `date` is not [today], drop
 * the counts and stamp the new day. Returns a new object on rollover
 * (so React `useState` setters notice) and the same reference when the
 * date already matches (so unrelated re-renders don't churn).
 */
export function rolloverIfNewDay(
  state: DailyCounters,
  today: string = todayKey(),
): DailyCounters {
  if (state.date === today) return state;
  return { date: today, counts: {} };
}

/**
 * Returns a new [DailyCounters] with [adId]'s count incremented by one.
 * Date-rollover-aware: if the input's date is stale, the increment lands
 * in a fresh same-day bag so a play that crosses midnight doesn't
 * silently land on yesterday's counter.
 *
 * Pure (no I/O). The localStorage write is owned by the React layer
 * (see `useDailyCounters` / `PlayerClient`) so this helper stays
 * trivially testable.
 */
export function incrementCount(
  state: DailyCounters,
  adId: string,
  today: string = todayKey(),
): DailyCounters {
  if (!adId) return state;
  const fresh = rolloverIfNewDay(state, today);
  const next = { ...fresh.counts };
  next[adId] = playsForAd(fresh.counts, adId) + 1;
  return { date: fresh.date, counts: next };
}

/** Build an empty same-day counter bag — used as the initial state. */
export function emptyCounters(today: string = todayKey()): DailyCounters {
  return { date: today, counts: {} };
}

/* ------------------------------------------------------------- persistence */

/** Storage-key generator. Per-device so multiple WebViews can coexist. */
export function storageKey(deviceId: string): string {
  return "adsignage:dailyCount:" + deviceId;
}

/**
 * Detect whether `localStorage` is usable. SSR (Next.js build) has no
 * `window`. A WebView with cookies/storage disabled throws on access.
 * Both cases collapse to "no persistence; in-memory only" rather than
 * surfacing the error to the operator.
 */
function hasStorage(): boolean {
  try {
    if (typeof window === "undefined") return false;
    if (!window.localStorage) return false;
    return true;
  } catch {
    return false;
  }
}

/**
 * Load (and date-rollover) the counters bag for [deviceId]. Always
 * returns a same-day [DailyCounters] — never throws. A missing,
 * unparseable, or schema-mismatched payload collapses to a fresh empty
 * bag stamped with today's date.
 */
export function loadCounters(deviceId: string): DailyCounters {
  const today = todayKey();
  if (!deviceId) return emptyCounters(today);
  if (!hasStorage()) return emptyCounters(today);
  let raw: string | null = null;
  try {
    raw = window.localStorage.getItem(storageKey(deviceId));
  } catch {
    return emptyCounters(today);
  }
  if (!raw) return emptyCounters(today);
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    return emptyCounters(today);
  }
  const normalised = normaliseStoredCounters(parsed);
  if (!normalised) return emptyCounters(today);
  return rolloverIfNewDay(normalised, today);
}

/**
 * Persist [state] for [deviceId]. Best-effort — a quota error or
 * disabled-storage WebView is logged at warn level and swallowed; the
 * in-memory state stays authoritative for the rest of the session.
 */
export function saveCounters(
  deviceId: string,
  state: DailyCounters,
): void {
  if (!deviceId) return;
  if (!hasStorage()) return;
  try {
    window.localStorage.setItem(storageKey(deviceId), JSON.stringify(state));
  } catch (err) {
    // Quota exceeded / disabled storage / private-browsing limits — not
    // fatal. The next reload starts fresh, which at worst lets one
    // capped ad play a few extra times.
    if (typeof console !== "undefined" && console.warn) {
      console.warn("[dailyCount] saveCounters failed", err);
    }
  }
}

/**
 * Coerce an arbitrary `JSON.parse` result into a well-typed
 * [DailyCounters] or `null` if the shape is unrecoverable. Defensive
 * because storage is the persistence boundary — anything could land
 * there from a previous app version, a manual edit, or a corrupted
 * profile. Exported for the test mirror.
 */
export function normaliseStoredCounters(value: unknown): DailyCounters | null {
  if (!value || typeof value !== "object") return null;
  const obj = value as Record<string, unknown>;
  const date = typeof obj.date === "string" ? obj.date : "";
  if (!/^\d{4}-\d{2}-\d{2}$/.test(date)) return null;
  const rawCounts = obj.counts;
  if (!rawCounts || typeof rawCounts !== "object") return null;
  const counts: Record<string, number> = {};
  for (const [k, v] of Object.entries(rawCounts as Record<string, unknown>)) {
    if (typeof v !== "number" || !Number.isFinite(v) || v < 0) continue;
    counts[k] = Math.floor(v);
  }
  return { date, counts };
}

/**
 * Internal exports for the node:test mirror. Not part of the public API.
 */
export const __test__ = {
  pad2,
  playsForAd,
};
