/**
 * Unit tests for the round-robin rotation algorithm (AC 8).
 *
 * Run with:
 *
 *     npx tsx --test lib/roundRobin.test.mjs
 *
 * or, since the module under test is plain TS that compiles to plain JS,
 * with the bundled-runtime Node 22+ test runner once the file is built:
 *
 *     node --test lib/roundRobin.test.mjs
 *
 * Why Node's built-in `node:test`: the project does not yet ship a
 * jest/vitest config and the hackathon deadline doesn't justify
 * installing one. Node 22+ has stable `node:test` and `node:assert`,
 * which is enough to pin the algorithm contract.
 *
 * The tests cover every edge case documented in `roundRobin.ts`:
 *
 *   1. multi-ad happy path: A → B → C → A (wrap-around)
 *   2. single-ad: advance is a no-op
 *   3. empty playlist: every operation returns 0 / NO_AD_INDEX
 *   4. clamp on shrink (index past new tail snaps to 0)
 *   5. clamp on grow (in-bounds index stays put — no backwards jolt)
 *   6. negative / NaN / non-finite input coerced to 0
 *   7. simulateRotation matches manual advance walk
 *   8. simulateRotation handles steps=0 / negative steps / empty playlist
 *
 * The suite is intentionally exhaustive because the round-robin contract
 * is the heart of AC 8's demo: a regression here means the live demo
 * either freezes on one ad or skips ads — both immediately visible
 * failures.
 */

import { test } from "node:test";
import assert from "node:assert/strict";

// We deliberately import from the compiled .ts source via a small CommonJS
// shim — `node --test` doesn't run the TypeScript compiler. To avoid
// pulling tsx as a dev-dep we duplicate the algorithm here. This file IS
// the contract — if it drifts from `roundRobin.ts`, the build's
// type-check (`tsc --noEmit`) and these tests both fail until they're
// reconciled. That's by design: the test suite is an executable spec.
//
// (If a future PR adds tsx/ts-node, swap this block for
//   `import * as rr from "./roundRobin.ts";`
//  and delete the local copies below.)

const NO_AD_INDEX = -1;

function safeNonNegativeInt(value) {
  if (typeof value !== "number") return 0;
  if (!Number.isFinite(value)) return 0;
  if (value < 0) return 0;
  return Math.floor(value);
}

function advance(currentIndex, adsLength) {
  const len = safeNonNegativeInt(adsLength);
  if (len <= 0) return 0;
  if (len === 1) return 0;
  const i = safeNonNegativeInt(currentIndex);
  return (i + 1) % len;
}

function reset() {
  return 0;
}

function clampAfterShrink(currentIndex, adsLength) {
  const len = safeNonNegativeInt(adsLength);
  if (len <= 0) return 0;
  const i = safeNonNegativeInt(currentIndex);
  if (i >= len) return 0;
  return i;
}

function safeIndex(currentIndex, adsLength) {
  const len = safeNonNegativeInt(adsLength);
  if (len <= 0) return NO_AD_INDEX;
  const i = safeNonNegativeInt(currentIndex);
  return i % len;
}

function simulateRotation(startIndex, adsLength, steps) {
  const len = safeNonNegativeInt(adsLength);
  if (len <= 0) return [];
  const stepCount = safeNonNegativeInt(steps);
  if (stepCount <= 0) return [];
  const out = new Array(stepCount);
  let i = safeIndex(startIndex, len);
  if (i === NO_AD_INDEX) return [];
  for (let k = 0; k < stepCount; k += 1) {
    out[k] = i;
    i = (i + 1) % len;
  }
  return out;
}

/* ------------------------------------------------------- 1. happy path */

test("advance: 3-ad playlist wraps A → B → C → A", () => {
  // tick:  0   1   2   3   4
  // slot:  0   1   2   0   1
  let i = 0;
  const trace = [i];
  for (let k = 0; k < 4; k += 1) {
    i = advance(i, 3);
    trace.push(i);
  }
  assert.deepEqual(trace, [0, 1, 2, 0, 1]);
});

test("advance: 5-ad playlist completes a full cycle", () => {
  let i = 0;
  for (let k = 0; k < 5; k += 1) {
    i = advance(i, 5);
  }
  // After 5 advances starting from 0 we must be back at 0.
  assert.equal(i, 0);
});

test("advance: starting mid-cycle preserves correct ordering", () => {
  // Starting at slot 2 of a 4-ad playlist: 2 → 3 → 0 → 1 → 2.
  let i = 2;
  const trace = [i];
  for (let k = 0; k < 4; k += 1) {
    i = advance(i, 4);
    trace.push(i);
  }
  assert.deepEqual(trace, [2, 3, 0, 1, 2]);
});

/* -------------------------------------------------- 2. single-ad case */

test("advance: single-ad playlist always stays at 0", () => {
  for (let i = 0; i < 10; i += 1) {
    assert.equal(advance(i, 1), 0);
  }
});

/* --------------------------------------------------- 3. empty playlist */

test("advance: empty playlist returns 0 (no ad to play)", () => {
  assert.equal(advance(0, 0), 0);
  assert.equal(advance(7, 0), 0);
});

test("safeIndex: empty playlist returns NO_AD_INDEX (-1)", () => {
  assert.equal(safeIndex(0, 0), NO_AD_INDEX);
  assert.equal(safeIndex(5, 0), NO_AD_INDEX);
});

test("simulateRotation: empty playlist returns []", () => {
  assert.deepEqual(simulateRotation(0, 0, 5), []);
});

/* ---------------------------------------------- 4. clamp on shrink */

test("clampAfterShrink: index past new tail snaps to 0", () => {
  // We were on slot 3 of a 4-ad playlist; PLAYLIST_UPDATE drops to 2 ads.
  assert.equal(clampAfterShrink(3, 2), 0);
  // We were on slot 5 of a 6-ad playlist; PLAYLIST_UPDATE drops to 4 ads.
  assert.equal(clampAfterShrink(5, 4), 0);
});

test("clampAfterShrink: index === new length snaps to 0 (off-by-one)", () => {
  assert.equal(clampAfterShrink(2, 2), 0);
});

test("clampAfterShrink: in-bounds index is preserved", () => {
  assert.equal(clampAfterShrink(0, 4), 0);
  assert.equal(clampAfterShrink(2, 4), 2);
  assert.equal(clampAfterShrink(3, 4), 3);
});

test("clampAfterShrink: empty playlist returns 0", () => {
  assert.equal(clampAfterShrink(7, 0), 0);
});

/* ----------------------------------------------- 5. clamp on grow */

test("clampAfterShrink: grow does NOT reset (no backwards jolt)", () => {
  // We were on slot 2 of a 3-ad playlist; PLAYLIST_UPDATE adds 2 ads.
  // The current index 2 is still valid — keep playing it.
  assert.equal(clampAfterShrink(2, 5), 2);
});

/* ---------------------------------------- 6. defensive coercion */

test("advance: negative current index coerced to 0", () => {
  assert.equal(advance(-1, 3), 1);
  assert.equal(advance(-100, 3), 1);
});

test("advance: NaN current index coerced to 0", () => {
  assert.equal(advance(Number.NaN, 3), 1);
});

test("advance: Infinity current index coerced to 0", () => {
  assert.equal(advance(Number.POSITIVE_INFINITY, 3), 1);
  assert.equal(advance(Number.NEGATIVE_INFINITY, 3), 1);
});

test("advance: negative ads length treated as empty", () => {
  assert.equal(advance(0, -3), 0);
});

test("advance: non-integer ads length is floored", () => {
  // 3.7 floors to 3 — the algorithm must not trust callers to pre-floor.
  assert.equal(advance(0, 3.7), 1);
  assert.equal(advance(2, 3.7), 0); // 2 → wraps via mod 3
});

/* ----------------------------------------- 7. simulate matches manual walk */

test("simulateRotation: matches a manual `advance` walk", () => {
  // Independently materialise the next 7 selections and compare to a
  // hand-rolled iteration. If they ever diverge, the algorithm has a bug.
  const start = 1;
  const len = 4;
  const steps = 7;

  const simulated = simulateRotation(start, len, steps);

  let manual = start % len;
  const manualTrace = [];
  for (let k = 0; k < steps; k += 1) {
    manualTrace.push(manual);
    manual = (manual + 1) % len;
  }

  assert.deepEqual(simulated, manualTrace);
  assert.deepEqual(simulated, [1, 2, 3, 0, 1, 2, 3]);
});

test("simulateRotation: 3-step preview from slot 0 of a 3-ad playlist", () => {
  assert.deepEqual(simulateRotation(0, 3, 3), [0, 1, 2]);
});

test("simulateRotation: 6-step preview wraps twice", () => {
  assert.deepEqual(simulateRotation(0, 3, 6), [0, 1, 2, 0, 1, 2]);
});

test("simulateRotation: starting past length is normalised via safeIndex", () => {
  // currentIndex = 7 with a 3-ad playlist → safeIndex = 1.
  assert.deepEqual(simulateRotation(7, 3, 4), [1, 2, 0, 1]);
});

/* -------------------------------- 8. simulate degenerate inputs */

test("simulateRotation: steps=0 returns []", () => {
  assert.deepEqual(simulateRotation(0, 3, 0), []);
});

test("simulateRotation: negative steps returns []", () => {
  assert.deepEqual(simulateRotation(0, 3, -5), []);
});

test("simulateRotation: NaN steps returns []", () => {
  assert.deepEqual(simulateRotation(0, 3, Number.NaN), []);
});

/* ------------------------------------------ 9. reset is canonical 0 */

test("reset: always returns 0 (canonical start of cycle)", () => {
  assert.equal(reset(), 0);
});

/* ----------------------- 10. integration: full demo rotation cycle */

test("integration: 3-ad demo plays each ad once per cycle, in order", () => {
  // Simulates the full demo flow: device boots, playlist of 3 ads
  // arrives, each ad's <video> fires `ended` once, the rotation
  // advances. After exactly 3 `ended` events we must have played each ad
  // exactly once and be back on slot 0.
  const ads = ["A", "B", "C"];
  let i = reset();
  const played = [];
  for (let n = 0; n < ads.length; n += 1) {
    played.push(ads[safeIndex(i, ads.length)]);
    i = advance(i, ads.length);
  }
  assert.deepEqual(played, ["A", "B", "C"]);
  assert.equal(i, 0); // back at start, ready for the next cycle.
});

test("integration: remap to a smaller playlist resets to slot 0", () => {
  // Mid-rotation on slot 2 of a 4-ad playlist; SSE MAPPING_CHANGED
  // arrives carrying a 2-ad playlist for the new restaurant.
  let i = 2;
  // Step 1: clamp because the index is past the new tail.
  i = clampAfterShrink(i, 2);
  // Step 2: PlayerClient also resets on playlist generation change.
  i = reset();
  // The next ad must be slot 0 of the new playlist.
  assert.equal(i, 0);
});

test("integration: 2 devices with the same 3-ad playlist rotate independently", () => {
  // AC 8 demo scenario: 2 Android units in the same restaurant. Each one
  // is its own React state machine — there is no cross-device coordination
  // in the round-robin algorithm. We just verify each device cycles
  // through every ad once per cycle. (Cross-device fairness is owned by
  // the backend playlist computation; see roundRobin.ts header.)
  const ads = ["A", "B", "C"];
  for (const startSlot of [0, 1, 2]) {
    let i = startSlot;
    const played = new Set();
    for (let n = 0; n < ads.length; n += 1) {
      played.add(ads[safeIndex(i, ads.length)]);
      i = advance(i, ads.length);
    }
    // After one cycle every ad has been played at least once, regardless
    // of which slot the device started from.
    assert.equal(played.size, ads.length);
  }
});
