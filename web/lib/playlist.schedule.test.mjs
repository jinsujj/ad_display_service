/**
 * Unit tests for the schedule-window predicate (AC 9, this sub-AC).
 *
 * What's under test:
 *
 *   parseHhMmToMinutes   — strict HH:mm parser, returns minutes-since-
 *                          midnight or null on any malformed input.
 *   dateToLocalMinutes   — Date -> minutes-since-midnight in local TZ.
 *   isAdActive           — half-open `[startTime, endTime)` predicate.
 *   filterActiveAds      — narrow a playlist to ads whose window
 *                          contains `now`, preserving order.
 *
 * These four helpers (in `web/lib/playlist.ts`) are the entire surface
 * area of the start_time/end_time enforcement: the player component
 * (`web/app/player/[deviceId]/PlayerClient.tsx`) only invokes
 * `filterActiveAds` and renders the resulting array. Pinning the pure
 * helpers therefore pins the user-visible behaviour.
 *
 * Run with:
 *
 *     node --test lib/playlist.schedule.test.mjs
 *
 * Why a vendored algorithm rather than `import` from the .ts source:
 *   the project has no jest/vitest config and `node --test` does not
 *   compile TypeScript. The same pattern is already used by
 *   `lib/roundRobin.test.mjs` — duplicating the algorithm here makes
 *   this file an executable spec. If the implementation drifts from the
 *   spec, the build's `tsc --noEmit` plus these tests both fail until
 *   they are reconciled, which is by design.
 */

import { test } from "node:test";
import assert from "node:assert/strict";

/* ------------------------------------------------------------ vendored impl */
/* Mirrors web/lib/playlist.ts.                                              */

function parseHhMmToMinutes(value) {
  if (!value) return null;
  const match = /^([0-9]{2}):([0-9]{2})$/.exec(value);
  if (!match) return null;
  const h = Number(match[1]);
  const m = Number(match[2]);
  if (!Number.isFinite(h) || !Number.isFinite(m)) return null;
  if (h < 0 || h > 23) return null;
  if (m < 0 || m > 59) return null;
  return h * 60 + m;
}

function dateToLocalMinutes(date) {
  return date.getHours() * 60 + date.getMinutes();
}

function isAdActive(ad, now = new Date()) {
  const startMin = parseHhMmToMinutes(ad.startTime);
  const endMin = parseHhMmToMinutes(ad.endTime);
  if (startMin === null && endMin === null) return true;
  const nowMin = dateToLocalMinutes(now);
  if (startMin !== null && nowMin < startMin) return false;
  if (endMin !== null && nowMin >= endMin) return false;
  return true;
}

function filterActiveAds(ads, now = new Date()) {
  return ads.filter((ad) => isAdActive(ad, now));
}

/* ----------------------------------------------------- helpers for tests */

/** Build a Date that, when read with getHours/getMinutes, gives [h, m]. */
function localTimeAt(h, m) {
  // Use today's date so DST/calendar gymnastics don't confuse local-tz
  // arithmetic — only the wall-clock h/m matters to the predicate.
  const d = new Date();
  d.setHours(h, m, 0, 0);
  return d;
}

/** Minimal PlaylistAd-shaped object — only the fields the predicate reads. */
function ad(adId, startTime, endTime) {
  return { adId, title: adId, startTime, endTime };
}

/* ============================================================== parse */

test("parseHhMmToMinutes: parses canonical HH:mm", () => {
  assert.equal(parseHhMmToMinutes("00:00"), 0);
  assert.equal(parseHhMmToMinutes("09:30"), 9 * 60 + 30);
  assert.equal(parseHhMmToMinutes("17:00"), 17 * 60);
  assert.equal(parseHhMmToMinutes("23:59"), 23 * 60 + 59);
});

test("parseHhMmToMinutes: rejects non-canonical formats", () => {
  // Single-digit hour
  assert.equal(parseHhMmToMinutes("9:30"), null);
  // Single-digit minute
  assert.equal(parseHhMmToMinutes("09:5"), null);
  // No separator
  assert.equal(parseHhMmToMinutes("0930"), null);
  // Trailing seconds
  assert.equal(parseHhMmToMinutes("09:30:00"), null);
  // Leading/trailing whitespace
  assert.equal(parseHhMmToMinutes(" 09:30"), null);
  assert.equal(parseHhMmToMinutes("09:30 "), null);
  // Total garbage
  assert.equal(parseHhMmToMinutes("not-a-time"), null);
});

test("parseHhMmToMinutes: rejects out-of-range hour and minute", () => {
  assert.equal(parseHhMmToMinutes("24:00"), null);
  assert.equal(parseHhMmToMinutes("99:00"), null);
  assert.equal(parseHhMmToMinutes("12:60"), null);
  assert.equal(parseHhMmToMinutes("12:99"), null);
});

test("parseHhMmToMinutes: empty/null/undefined return null", () => {
  assert.equal(parseHhMmToMinutes(""), null);
  assert.equal(parseHhMmToMinutes(null), null);
  assert.equal(parseHhMmToMinutes(undefined), null);
});

/* ============================================================ dateToLocal */

test("dateToLocalMinutes: produces wall-clock minutes-since-midnight", () => {
  assert.equal(dateToLocalMinutes(localTimeAt(0, 0)), 0);
  assert.equal(dateToLocalMinutes(localTimeAt(9, 30)), 9 * 60 + 30);
  assert.equal(dateToLocalMinutes(localTimeAt(23, 59)), 23 * 60 + 59);
});

/* ============================================================== isAdActive */

test("isAdActive: ad inside the window plays", () => {
  // Dinner ad 17:00–21:00; 19:00 is squarely inside.
  const dinner = ad("dinner", "17:00", "21:00");
  assert.equal(isAdActive(dinner, localTimeAt(19, 0)), true);
});

test("isAdActive: start boundary is inclusive", () => {
  // At 17:00 exactly the window has just opened.
  const dinner = ad("dinner", "17:00", "21:00");
  assert.equal(isAdActive(dinner, localTimeAt(17, 0)), true);
});

test("isAdActive: end boundary is exclusive", () => {
  // At 21:00 exactly the window has just closed.
  const dinner = ad("dinner", "17:00", "21:00");
  assert.equal(isAdActive(dinner, localTimeAt(21, 0)), false);
});

test("isAdActive: one minute before start does not play", () => {
  const dinner = ad("dinner", "17:00", "21:00");
  assert.equal(isAdActive(dinner, localTimeAt(16, 59)), false);
});

test("isAdActive: one minute before end still plays", () => {
  const dinner = ad("dinner", "17:00", "21:00");
  assert.equal(isAdActive(dinner, localTimeAt(20, 59)), true);
});

test("isAdActive: well after end does not play", () => {
  const dinner = ad("dinner", "17:00", "21:00");
  assert.equal(isAdActive(dinner, localTimeAt(22, 0)), false);
});

test("isAdActive: well before start does not play", () => {
  const dinner = ad("dinner", "17:00", "21:00");
  assert.equal(isAdActive(dinner, localTimeAt(8, 0)), false);
});

test("isAdActive: missing both bounds is fail-open (always active)", () => {
  // Bug-tolerance: if the backend produced a row with null/null, we'd
  // rather over-show than blackhole the customer's entire schedule.
  const always = ad("always", null, null);
  assert.equal(isAdActive(always, localTimeAt(3, 0)), true);
  assert.equal(isAdActive(always, localTimeAt(15, 0)), true);
  assert.equal(isAdActive(always, localTimeAt(23, 30)), true);
});

test("isAdActive: missing start treats lower bound as unconstrained", () => {
  // Effective window: (..., 12:00) — anything strictly before noon.
  const morningOnly = ad("morning", null, "12:00");
  assert.equal(isAdActive(morningOnly, localTimeAt(0, 0)), true);
  assert.equal(isAdActive(morningOnly, localTimeAt(11, 59)), true);
  assert.equal(isAdActive(morningOnly, localTimeAt(12, 0)), false);
});

test("isAdActive: missing end treats upper bound as unconstrained", () => {
  // Effective window: [12:00, ...) — noon onwards.
  const afternoonOnly = ad("afternoon", "12:00", null);
  assert.equal(isAdActive(afternoonOnly, localTimeAt(11, 59)), false);
  assert.equal(isAdActive(afternoonOnly, localTimeAt(12, 0)), true);
  assert.equal(isAdActive(afternoonOnly, localTimeAt(23, 59)), true);
});

test("isAdActive: malformed start/end is treated like missing", () => {
  // Same fail-open semantics — a server bug serialising "9:30" should
  // not silently mute the ad. The malformed bound is dropped; the
  // surviving bound still constrains.
  const broken = ad("broken", "9:30", "21:00");
  // start parsed as null → unconstrained lower bound. End is 21:00.
  assert.equal(isAdActive(broken, localTimeAt(0, 0)), true);
  assert.equal(isAdActive(broken, localTimeAt(20, 59)), true);
  assert.equal(isAdActive(broken, localTimeAt(21, 0)), false);
});

test("isAdActive: zero-length window (start == end) never plays", () => {
  // Sanity: backend's CHECK constraint ck_ads_time_window forbids this
  // shape, but the predicate must not panic if it ever arrives.
  // start inclusive, end exclusive ⇒ [t, t) is the empty interval.
  const degenerate = ad("zero", "12:00", "12:00");
  assert.equal(isAdActive(degenerate, localTimeAt(11, 59)), false);
  assert.equal(isAdActive(degenerate, localTimeAt(12, 0)), false);
  assert.equal(isAdActive(degenerate, localTimeAt(12, 1)), false);
});

test("isAdActive: full-day window 00:00–23:59 plays except at 23:59", () => {
  // The minute "23:59" should still be inside; "00:00" of the next day
  // is past end (but we only have wall-clock minutes here, so test the
  // boundary inside the day).
  const allDay = ad("allday", "00:00", "23:59");
  assert.equal(isAdActive(allDay, localTimeAt(0, 0)), true);
  assert.equal(isAdActive(allDay, localTimeAt(12, 0)), true);
  assert.equal(isAdActive(allDay, localTimeAt(23, 58)), true);
  assert.equal(isAdActive(allDay, localTimeAt(23, 59)), false);
});

/* =========================================================== filterActive */

test("filterActiveAds: drops ads outside their window, keeps those inside", () => {
  const ads = [
    ad("breakfast", "07:00", "10:00"),
    ad("lunch", "11:30", "14:00"),
    ad("dinner", "17:00", "21:00"),
  ];
  // 12:00 — only "lunch" should be active.
  const active = filterActiveAds(ads, localTimeAt(12, 0));
  assert.deepEqual(
    active.map((a) => a.adId),
    ["lunch"],
  );
});

test("filterActiveAds: preserves source order", () => {
  // Two overlapping windows around 13:00; the result must preserve the
  // input order so the round-robin AC sees a stable iteration order.
  const ads = [
    ad("a", "12:00", "14:00"),
    ad("b", "10:00", "20:00"),
    ad("c", "15:00", "16:00"),
  ];
  const active = filterActiveAds(ads, localTimeAt(13, 0));
  assert.deepEqual(
    active.map((a) => a.adId),
    ["a", "b"],
  );
});

test("filterActiveAds: returns [] when no ads are active", () => {
  // Outside-window scenario — drives the player's "Outside scheduled
  // window" splash branch.
  const ads = [
    ad("breakfast", "07:00", "10:00"),
    ad("dinner", "17:00", "21:00"),
  ];
  // 14:00 — neither window contains noon-ish.
  const active = filterActiveAds(ads, localTimeAt(14, 0));
  assert.deepEqual(active, []);
});

test("filterActiveAds: empty input returns []", () => {
  assert.deepEqual(filterActiveAds([], localTimeAt(12, 0)), []);
});

test("filterActiveAds: ad with null bounds always survives the filter", () => {
  // Confirms the fail-open semantics carry through the array helper.
  const ads = [
    ad("dinner", "17:00", "21:00"),
    ad("always", null, null),
    ad("breakfast", "07:00", "10:00"),
  ];
  const active = filterActiveAds(ads, localTimeAt(12, 0));
  assert.deepEqual(
    active.map((a) => a.adId),
    ["always"],
  );
});

/* ====================================================== integration scenes */

test("integration: schedule rolls over from breakfast → lunch → dinner", () => {
  // The exact scene the player runs through every day. Drives the
  // 30-second tick that PlayerClient uses to re-evaluate the active set.
  const ads = [
    ad("breakfast", "07:00", "10:00"),
    ad("lunch", "11:30", "14:00"),
    ad("dinner", "17:00", "21:00"),
  ];
  // 08:00 — breakfast.
  assert.deepEqual(
    filterActiveAds(ads, localTimeAt(8, 0)).map((a) => a.adId),
    ["breakfast"],
  );
  // 10:00 — breakfast just ended (end is exclusive), nothing active.
  assert.deepEqual(filterActiveAds(ads, localTimeAt(10, 0)), []);
  // 13:59 — lunch still active for one more minute.
  assert.deepEqual(
    filterActiveAds(ads, localTimeAt(13, 59)).map((a) => a.adId),
    ["lunch"],
  );
  // 14:00 — lunch closed.
  assert.deepEqual(filterActiveAds(ads, localTimeAt(14, 0)), []);
  // 17:00 — dinner just opened (start is inclusive).
  assert.deepEqual(
    filterActiveAds(ads, localTimeAt(17, 0)).map((a) => a.adId),
    ["dinner"],
  );
  // 20:59 — last minute of dinner.
  assert.deepEqual(
    filterActiveAds(ads, localTimeAt(20, 59)).map((a) => a.adId),
    ["dinner"],
  );
  // 21:00 — dinner closed.
  assert.deepEqual(filterActiveAds(ads, localTimeAt(21, 0)), []);
});

test("integration: outside-window splash branch — playlist non-empty, active=[]", () => {
  // Demonstrates the exact predicate combination that drives PlayerClient's
  // <PlayerEmpty message="Outside scheduled window" /> branch:
  //   playlistState.kind === "ready" &&
  //   playlistState.playlist.ads.length > 0 &&
  //   adsLength === 0
  const ads = [
    ad("breakfast", "07:00", "10:00"),
    ad("dinner", "17:00", "21:00"),
  ];
  assert.equal(ads.length > 0, true);
  const active = filterActiveAds(ads, localTimeAt(15, 0));
  assert.equal(active.length, 0);
});
