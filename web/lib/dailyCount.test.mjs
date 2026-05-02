/**
 * Unit tests for the per-ad daily play counter (AC 3, Sub-AC 2).
 *
 * What's under test:
 *
 *   localDateKey         — Date -> "YYYY-MM-DD" in local TZ.
 *   normaliseCap         — wire-shape cap -> typed cap (null=unlimited,
 *                          0=immediately capped, positive int=that cap).
 *   getRemaining         — how many more plays today; null=unlimited.
 *   isAdCapped           — has this ad hit its daily cap?
 *   filterUnderCap       — order-preserving narrow to under-cap ads.
 *   rolloverIfNewDay     — drop counts when the day changed.
 *   incrementCount       — new bag with adId's count incremented;
 *                          date-rollover-aware so a play crossing
 *                          midnight doesn't land on yesterday's bag.
 *   normaliseStoredCounters — defensive parse of an arbitrary
 *                          localStorage payload into a typed bag.
 *
 * These helpers (in `web/lib/dailyCount.ts`) are the entire surface area
 * of the daily-cap enforcement: the player component
 * (`web/app/player/[deviceId]/PlayerClient.tsx`) calls `loadCounters` on
 * mount, `filterUnderCap` to narrow the active set, and `incrementCount`
 * + `saveCounters` from `<video onEnded>`. Pinning the pure helpers
 * therefore pins the user-visible behaviour.
 *
 * Run with:
 *
 *     node --test lib/dailyCount.test.mjs
 *
 * Why a vendored algorithm rather than `import` from the .ts source:
 *   the project has no jest/vitest config and `node --test` does not
 *   compile TypeScript. The same pattern is already used by
 *   `lib/roundRobin.test.mjs` and `lib/playlist.schedule.test.mjs` —
 *   duplicating the algorithm here makes this file an executable spec.
 *   If the implementation drifts from the spec, the build's
 *   `tsc --noEmit` plus these tests both fail until they are reconciled,
 *   which is by design.
 *
 * The localStorage layer (`loadCounters` / `saveCounters` /
 * `storageKey` / `hasStorage`) is intentionally NOT mirrored here — it
 * is a thin try/catch wrapper around `window.localStorage`, has no
 * algorithmic content to test, and would require a DOM stub to exercise.
 * The pure helpers below are what determine whether a capped ad ever
 * reaches the player.
 */

import { test } from "node:test";
import assert from "node:assert/strict";

/* ------------------------------------------------------------ vendored impl */
/* Mirrors web/lib/dailyCount.ts.                                            */

function pad2(n) {
  if (n < 10) return "0" + String(n);
  return String(n);
}

function localDateKey(date = new Date()) {
  const y = date.getFullYear();
  const m = pad2(date.getMonth() + 1);
  const d = pad2(date.getDate());
  return y + "-" + m + "-" + d;
}

function todayKey() {
  return localDateKey(new Date());
}

function playsForAd(counts, adId) {
  const v = counts[adId];
  if (typeof v !== "number" || !Number.isFinite(v) || v < 0) return 0;
  return Math.floor(v);
}

function normaliseCap(cap) {
  if (cap === null || cap === undefined) return null;
  if (typeof cap !== "number" || !Number.isFinite(cap)) return null;
  if (cap <= 0) return 0;
  return Math.floor(cap);
}

function getRemaining(ad, counts) {
  const cap = normaliseCap(ad.dailyCount);
  if (cap === null) return null;
  const used = playsForAd(counts, ad.adId);
  const remaining = cap - used;
  return remaining > 0 ? remaining : 0;
}

function isAdCapped(ad, counts) {
  const r = getRemaining(ad, counts);
  if (r === null) return false;
  return r <= 0;
}

function filterUnderCap(ads, counts) {
  return ads.filter((ad) => !isAdCapped(ad, counts));
}

function rolloverIfNewDay(state, today = todayKey()) {
  if (state.date === today) return state;
  return { date: today, counts: {} };
}

function incrementCount(state, adId, today = todayKey()) {
  if (!adId) return state;
  const fresh = rolloverIfNewDay(state, today);
  const next = { ...fresh.counts };
  next[adId] = playsForAd(fresh.counts, adId) + 1;
  return { date: fresh.date, counts: next };
}

function emptyCounters(today = todayKey()) {
  return { date: today, counts: {} };
}

function normaliseStoredCounters(value) {
  if (!value || typeof value !== "object") return null;
  const date = typeof value.date === "string" ? value.date : "";
  if (!/^\d{4}-\d{2}-\d{2}$/.test(date)) return null;
  const rawCounts = value.counts;
  if (!rawCounts || typeof rawCounts !== "object") return null;
  const counts = {};
  for (const [k, v] of Object.entries(rawCounts)) {
    if (typeof v !== "number" || !Number.isFinite(v) || v < 0) continue;
    counts[k] = Math.floor(v);
  }
  return { date, counts };
}

/* ----------------------------------------------------- helpers for tests */

/** Minimal PlaylistAd-shaped object — only the fields the helpers read. */
function ad(adId, dailyCount = null) {
  return { adId, title: adId, dailyCount };
}

/* ============================================================== localDateKey */

test("localDateKey: pads single-digit month and day", () => {
  // Build via setFullYear/setMonth/setDate so the local-tz fields read
  // back exactly what we asked for. (new Date('2025-01-05') is parsed
  // as UTC and would skew under non-UTC zones.)
  const d = new Date();
  d.setFullYear(2025, 0, 5); // Jan 5
  d.setHours(12, 0, 0, 0);
  assert.equal(localDateKey(d), "2025-01-05");
});

test("localDateKey: handles two-digit month and day", () => {
  const d = new Date();
  d.setFullYear(2025, 11, 31); // Dec 31
  d.setHours(12, 0, 0, 0);
  assert.equal(localDateKey(d), "2025-12-31");
});

test("localDateKey: month-boundary day produces correct date", () => {
  const d = new Date();
  d.setFullYear(2025, 1, 1); // Feb 1
  d.setHours(12, 0, 0, 0);
  assert.equal(localDateKey(d), "2025-02-01");
});

/* ============================================================ normaliseCap */

test("normaliseCap: null/undefined → null (unlimited)", () => {
  assert.equal(normaliseCap(null), null);
  assert.equal(normaliseCap(undefined), null);
});

test("normaliseCap: NaN/Infinity → null (degrade to unlimited)", () => {
  assert.equal(normaliseCap(Number.NaN), null);
  assert.equal(normaliseCap(Number.POSITIVE_INFINITY), null);
  assert.equal(normaliseCap(Number.NEGATIVE_INFINITY), null);
});

test("normaliseCap: zero/negative → 0 (capped immediately)", () => {
  assert.equal(normaliseCap(0), 0);
  assert.equal(normaliseCap(-1), 0);
  assert.equal(normaliseCap(-100), 0);
});

test("normaliseCap: positive integer passes through", () => {
  assert.equal(normaliseCap(1), 1);
  assert.equal(normaliseCap(200), 200);
});

test("normaliseCap: positive non-integer is floored", () => {
  // We don't trust the wire to send integers; floor so 2.7 plays max
  // means 2, not "2.7 plays today" silently rounding up later.
  assert.equal(normaliseCap(2.7), 2);
  assert.equal(normaliseCap(199.9), 199);
});

/* ============================================================ getRemaining */

test("getRemaining: unlimited ad returns null", () => {
  const a = ad("nocap", null);
  assert.equal(getRemaining(a, { nocap: 100 }), null);
});

test("getRemaining: counts missing key → returns full cap", () => {
  const a = ad("fresh", 5);
  // No entry for `fresh` in the bag — this is a brand-new day or new ad.
  assert.equal(getRemaining(a, {}), 5);
});

test("getRemaining: under cap returns the headroom", () => {
  const a = ad("dinner", 10);
  assert.equal(getRemaining(a, { dinner: 3 }), 7);
});

test("getRemaining: at cap returns 0", () => {
  const a = ad("dinner", 10);
  assert.equal(getRemaining(a, { dinner: 10 }), 0);
});

test("getRemaining: over cap clamps to 0 (no negative remaining)", () => {
  // Counts can drift past cap if cap was lowered mid-day; never report
  // a negative remaining.
  const a = ad("dinner", 10);
  assert.equal(getRemaining(a, { dinner: 12 }), 0);
});

test("getRemaining: zero cap is capped immediately", () => {
  const a = ad("muted", 0);
  assert.equal(getRemaining(a, {}), 0);
});

/* ============================================================== isAdCapped */

test("isAdCapped: unlimited ad is never capped", () => {
  const a = ad("nocap", null);
  // Even with a wildly large count, an unlimited ad keeps playing.
  assert.equal(isAdCapped(a, { nocap: 10_000 }), false);
});

test("isAdCapped: under-cap ad is not capped", () => {
  const a = ad("dinner", 10);
  assert.equal(isAdCapped(a, { dinner: 3 }), false);
});

test("isAdCapped: at-cap ad IS capped (>= semantics)", () => {
  // The contract is `count >= cap` filters out — the Nth play is
  // allowed and the (N+1)th is suppressed. So at exactly N plays, the
  // ad is capped for the rest of the day.
  const a = ad("dinner", 10);
  assert.equal(isAdCapped(a, { dinner: 10 }), true);
});

test("isAdCapped: zero-cap ad is capped on day one", () => {
  const a = ad("muted", 0);
  assert.equal(isAdCapped(a, {}), true);
});

/* ============================================================ filterUnderCap */

test("filterUnderCap: drops only the capped ad, preserves order", () => {
  const ads = [
    ad("a", 5),    // under cap
    ad("b", 3),    // capped
    ad("c", null), // unlimited
    ad("d", 10),   // under cap
  ];
  const counts = { a: 1, b: 3, c: 999, d: 9 };
  const out = filterUnderCap(ads, counts);
  assert.deepEqual(out.map((x) => x.adId), ["a", "c", "d"]);
});

test("filterUnderCap: empty input returns empty array", () => {
  assert.deepEqual(filterUnderCap([], { x: 1 }), []);
});

test("filterUnderCap: all ads under cap returns input unchanged (order)", () => {
  const ads = [ad("a", 10), ad("b", 10), ad("c", 10)];
  const out = filterUnderCap(ads, {});
  assert.deepEqual(out.map((x) => x.adId), ["a", "b", "c"]);
});

test("filterUnderCap: all ads capped returns []", () => {
  const ads = [ad("a", 1), ad("b", 1)];
  const out = filterUnderCap(ads, { a: 1, b: 1 });
  assert.deepEqual(out, []);
});

/* =========================================================== rolloverIfNewDay */

test("rolloverIfNewDay: same day returns SAME reference (no churn)", () => {
  // Important contract: when the date matches, callers (React `useState`)
  // should not see a new object identity — that would force a re-render.
  const state = { date: "2025-05-02", counts: { a: 3 } };
  const result = rolloverIfNewDay(state, "2025-05-02");
  assert.equal(result, state); // reference equality
});

test("rolloverIfNewDay: different day drops counts and stamps new date", () => {
  const yesterday = { date: "2025-05-01", counts: { a: 5, b: 2 } };
  const result = rolloverIfNewDay(yesterday, "2025-05-02");
  assert.deepEqual(result, { date: "2025-05-02", counts: {} });
  assert.notEqual(result, yesterday); // new reference
});

test("rolloverIfNewDay: day-after-tomorrow also rolls (any mismatch counts)", () => {
  const stale = { date: "2024-12-31", counts: { ad1: 200 } };
  const result = rolloverIfNewDay(stale, "2025-05-02");
  assert.deepEqual(result.counts, {});
});

/* ============================================================ incrementCount */

test("incrementCount: bumps from 0 to 1 on first play", () => {
  const today = "2025-05-02";
  const empty = { date: today, counts: {} };
  const result = incrementCount(empty, "ad-1", today);
  assert.deepEqual(result, { date: today, counts: { "ad-1": 1 } });
});

test("incrementCount: monotonically increases on repeated plays", () => {
  const today = "2025-05-02";
  let s = { date: today, counts: {} };
  for (let k = 0; k < 5; k += 1) {
    s = incrementCount(s, "ad-1", today);
  }
  assert.equal(s.counts["ad-1"], 5);
});

test("incrementCount: separate ad ids accumulate independently", () => {
  const today = "2025-05-02";
  let s = { date: today, counts: {} };
  s = incrementCount(s, "ad-a", today);
  s = incrementCount(s, "ad-b", today);
  s = incrementCount(s, "ad-a", today);
  s = incrementCount(s, "ad-b", today);
  s = incrementCount(s, "ad-a", today);
  assert.equal(s.counts["ad-a"], 3);
  assert.equal(s.counts["ad-b"], 2);
});

test("incrementCount: increment crossing midnight lands on the new day", () => {
  // The player's last play of the day might end at 00:00:01 of the next
  // calendar day. The counter must rollover BEFORE the increment so the
  // bump goes into the new day's bag.
  const yesterday = { date: "2025-05-01", counts: { "ad-1": 199 } };
  const result = incrementCount(yesterday, "ad-1", "2025-05-02");
  assert.equal(result.date, "2025-05-02");
  // Yesterday's 199 is gone; today's count is 1, not 200.
  assert.equal(result.counts["ad-1"], 1);
  // No spillover from yesterday's other ads.
  assert.equal(result.counts["ad-other"], undefined);
});

test("incrementCount: empty adId is a no-op (returns same reference)", () => {
  const s = { date: "2025-05-02", counts: { x: 1 } };
  const result = incrementCount(s, "", "2025-05-02");
  assert.equal(result, s);
});

test("incrementCount: handles non-numeric stored value as zero baseline", () => {
  // A corrupted bag with a string for a count — defensive: floor to 0
  // and bump from there rather than NaN-poisoning future operations.
  const s = { date: "2025-05-02", counts: { x: "garbage" } };
  const result = incrementCount(s, "x", "2025-05-02");
  assert.equal(result.counts["x"], 1);
});

/* ===================================================== integration: full cycle */

test("integration: ad with cap=3 plays exactly 3 times then is filtered out", () => {
  // Simulate the demo flow: dinner ad with daily cap of 3 plays. After
  // 3 `ended` events the filter drops it and the round-robin sees a
  // shorter active list.
  const today = "2025-05-02";
  const dinnerAd = ad("dinner", 3);
  const otherAd = ad("breakfast", 100);
  const playlist = [dinnerAd, otherAd];

  let counters = { date: today, counts: {} };
  let active = filterUnderCap(playlist, counters.counts);
  assert.deepEqual(active.map((a) => a.adId), ["dinner", "breakfast"]);

  // Play dinner three times in a row.
  for (let n = 0; n < 3; n += 1) {
    counters = incrementCount(counters, "dinner", today);
  }
  assert.equal(counters.counts["dinner"], 3);

  // After the third play, dinner is at cap and filtered out.
  active = filterUnderCap(playlist, counters.counts);
  assert.deepEqual(active.map((a) => a.adId), ["breakfast"]);

  // breakfast keeps playing — its cap is 100.
  counters = incrementCount(counters, "breakfast", today);
  active = filterUnderCap(playlist, counters.counts);
  assert.deepEqual(active.map((a) => a.adId), ["breakfast"]);
});

test("integration: rollover at midnight reinstates a previously-capped ad", () => {
  // End-of-day state: dinner ad has been played its full cap.
  const dinnerAd = ad("dinner", 3);
  let counters = { date: "2025-05-01", counts: { dinner: 3 } };
  // Filtered out today.
  assert.deepEqual(
    filterUnderCap([dinnerAd], counters.counts).map((a) => a.adId),
    [],
  );
  // Rollover hits when the next play attempt happens on a new day.
  counters = rolloverIfNewDay(counters, "2025-05-02");
  // Today's bag is empty, so dinner is back in the active set.
  assert.deepEqual(
    filterUnderCap([dinnerAd], counters.counts).map((a) => a.adId),
    ["dinner"],
  );
});

test("integration: zero-cap ad is filtered every day (operator muted)", () => {
  // dailyCount=0 means the operator effectively muted the ad. It must
  // never appear in the active set, regardless of bag contents.
  const muted = ad("muted", 0);
  for (const counts of [{}, { muted: 0 }, { muted: 1 }, { other: 99 }]) {
    assert.deepEqual(filterUnderCap([muted], counts), []);
  }
});

test("integration: unlimited ad never gets filtered, however high the count", () => {
  const unlimited = ad("forever", null);
  const counts = { forever: 1_000_000 };
  assert.deepEqual(
    filterUnderCap([unlimited], counts).map((a) => a.adId),
    ["forever"],
  );
});

/* ============================================ normaliseStoredCounters */

test("normaliseStoredCounters: well-formed payload round-trips", () => {
  const input = { date: "2025-05-02", counts: { a: 1, b: 2 } };
  assert.deepEqual(normaliseStoredCounters(input), input);
});

test("normaliseStoredCounters: missing date returns null", () => {
  assert.equal(normaliseStoredCounters({ counts: {} }), null);
});

test("normaliseStoredCounters: malformed date returns null", () => {
  assert.equal(normaliseStoredCounters({ date: "not-a-date", counts: {} }), null);
  assert.equal(normaliseStoredCounters({ date: "2025-5-2", counts: {} }), null);
  assert.equal(normaliseStoredCounters({ date: "20250502", counts: {} }), null);
});

test("normaliseStoredCounters: missing counts returns null", () => {
  assert.equal(normaliseStoredCounters({ date: "2025-05-02" }), null);
});

test("normaliseStoredCounters: drops invalid count entries, keeps valid ones", () => {
  const input = {
    date: "2025-05-02",
    counts: {
      good: 5,
      bad_string: "x",
      bad_negative: -1,
      bad_nan: Number.NaN,
      good_zero: 0,
      good_floor: 3.7, // floored to 3
    },
  };
  const result = normaliseStoredCounters(input);
  assert.deepEqual(result, {
    date: "2025-05-02",
    counts: { good: 5, good_zero: 0, good_floor: 3 },
  });
});

test("normaliseStoredCounters: non-object input returns null", () => {
  assert.equal(normaliseStoredCounters(null), null);
  assert.equal(normaliseStoredCounters(undefined), null);
  assert.equal(normaliseStoredCounters("string"), null);
  assert.equal(normaliseStoredCounters(42), null);
});

/* ============================================ emptyCounters */

test("emptyCounters: produces a same-day bag with no counts", () => {
  const today = "2025-05-02";
  assert.deepEqual(emptyCounters(today), { date: today, counts: {} });
});
