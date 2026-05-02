/**
 * Round-robin rotation algorithm for the device playlist.
 *
 * Owns AC 8: "Multiple ads on one device rotate via round-robin algorithm".
 *
 * ## What round-robin means here
 *
 * Given an ordered playlist `ads = [A, B, C, …]` of length N, the device
 * plays each ad to completion ONCE per cycle, then advances to the next
 * slot, wrapping around after the last:
 *
 *   tick:  0   1   2   3   4   5   6   …
 *   slot:  0   1   2   0   1   2   0   …
 *   ad:    A   B   C   A   B   C   A   …
 *
 * Equivalently: `slot(tick+1) = (slot(tick) + 1) mod N`. The algorithm
 * is independent of WHEN each ad ends — every ad gets exactly one play
 * per cycle, in the order the backend emitted them.
 *
 * ## Why this lives in its own module (not inlined in `PlayerClient`)
 *
 * `PlayerClient` is the React surface that wires DOM events (`onEnded`,
 * `onError`) and SSE-driven playlist refetches into the rotation state.
 * Mixing the rotation arithmetic with that I/O makes the algorithm hard
 * to reason about and impossible to unit-test without a DOM or
 * EventSource stub.
 *
 * Extracting a pure, side-effect-free module:
 *   - centralises the round-robin contract in ONE place an auditor can
 *     read end-to-end,
 *   - makes `simulateRotation` (a deterministic preview of the next N
 *     selections) trivial to expose for the demo overlay and tests,
 *   - lets us run a `node --test` suite that pins the algorithm against
 *     all the edge cases below, so a future refactor that subtly breaks
 *     wrap-around behaviour fails CI, not the live demo.
 *
 * ## Edge cases the algorithm handles
 *
 *   1. **Empty playlist** (`adsLength === 0`): every operation is a
 *      no-op and returns `0`. The caller (`PlayerClient`) renders the
 *      "no ads scheduled" splash and never tries to play a video, so the
 *      index value is irrelevant — but we keep it deterministic so that
 *      a transient empty-state doesn't surface a `NaN` or stale value to
 *      the status overlay.
 *
 *   2. **Single-ad playlist** (`adsLength === 1`): `advance` always
 *      returns `0`. (`PlayerClient` short-circuits via `<video loop>` in
 *      this case to avoid an end/restart flicker, but the algorithm
 *      still works correctly if `loop` is somehow off — the `ended`
 *      event would just keep advancing to the same slot.)
 *
 *   3. **Playlist shrank under the live index** (`currentIndex >=
 *      adsLength`): a `PLAYLIST_UPDATE` event can drop ads while one is
 *      mid-rotation. `clampAfterShrink` snaps the index back to `0` so
 *      the next paint picks a valid ad rather than rendering nothing
 *      until the next `ended`.
 *
 *   4. **Playlist grew under the live index**: the new ads sit at the
 *      tail of the array. The current index stays valid (it's already
 *      within bounds), so we keep playing the in-flight ad and the new
 *      ads are visited naturally on subsequent `advance`s. There is
 *      deliberately NO logic to "restart from 0 on grow" — that would
 *      jolt the demo backwards.
 *
 *   5. **Same-shape playlist replaced** (mapping change to a restaurant
 *      whose schedule happens to have the same ad count): the caller
 *      pairs `advance` with a `reset()` keyed off the playlist
 *      generation id (`loadedAt` in `PlayerClient`), so a remap always
 *      starts from slot 0 even when `clampAfterShrink` would otherwise
 *      not fire.
 *
 *   6. **Negative indices / NaN**: defensive — every helper coerces a
 *      bad input to `0`. We never trust a stale or corrupted state value
 *      to keep playback alive.
 *
 * ## What this module is NOT responsible for
 *
 *   - **Schedule-window filtering** (HH:mm + dailyCount). Owned by the
 *     backend playlist endpoint; by the time the playlist reaches this
 *     module, `ads` is already the active set we should rotate through.
 *
 *   - **Fairness across devices** (e.g. distributing daily-count plays
 *     across multiple devices in the same restaurant). Owned by the
 *     backend playlist computation; this module is per-device.
 *
 *   - **Skipping broken videos**. Handled by `PlayerClient` via
 *     `onError → advance`; this module is content-agnostic.
 *
 * ## Algorithm complexity
 *
 * Every operation is `O(1)` — round-robin is, by design, the cheapest
 * possible rotation algorithm. `simulateRotation` is `O(steps)` and is
 * intended for short previews (≤ 32 entries), not for materialising
 * long traces.
 */

/** Sentinel value the caller treats as "no ad to play right now". */
export const NO_AD_INDEX = -1 as const;

/**
 * Coerce an arbitrary value into a non-negative finite integer, or `0`
 * if it isn't one. Used at the boundary so a corrupted React state
 * value (e.g. from an out-of-tree error) can't propagate `NaN` into the
 * `<video>` selection.
 */
function safeNonNegativeInt(value: unknown): number {
  if (typeof value !== "number") return 0;
  if (!Number.isFinite(value)) return 0;
  if (value < 0) return 0;
  return Math.floor(value);
}

/**
 * Returns the slot index that should play after the current one ends.
 *
 * - `adsLength <= 0`         → `0`  (empty playlist; index is irrelevant)
 * - `adsLength === 1`        → `0`  (single-ad: no rotation needed)
 * - otherwise                → `(currentIndex + 1) mod adsLength`
 *
 * Wired to the `<video>`'s `ended` event in `PlayerClient`. Implementing
 * this as a pure function — rather than `setCurrentIndex(i => …)` inline —
 * lets us pin the behaviour in unit tests below.
 */
export function advance(currentIndex: number, adsLength: number): number {
  const len = safeNonNegativeInt(adsLength);
  if (len <= 0) return 0;
  if (len === 1) return 0;
  const i = safeNonNegativeInt(currentIndex);
  return (i + 1) % len;
}

/**
 * Reset the rotation index to the start of the playlist. Called by
 * `PlayerClient` whenever the playlist generation changes (initial
 * fetch, mapping change, in-place playlist update) so a remap always
 * begins from the first ad of the new restaurant.
 *
 * Returns the canonical "start" value (`0`) — promoted to a function
 * rather than a constant so callers can substitute a different policy
 * later (e.g. resume-where-you-left-off after a brief disconnect)
 * without touching every call site.
 */
export function reset(): number {
  return 0;
}

/**
 * Clamp a possibly-out-of-range index into the current playlist's
 * bounds. Idempotent: calling it on an already-valid index returns the
 * same value.
 *
 * Triggered when the playlist length changed underneath the live
 * rotation (most commonly a `PLAYLIST_UPDATE` that drops an ad). If the
 * shrink left the index past the new tail, snap back to `0`; otherwise
 * keep the in-flight ad. We deliberately do NOT clamp to `adsLength-1`
 * because the player has already consumed `currentIndex` for this
 * cycle — restarting from the new first ad is the only behaviour that
 * doesn't cause a backwards jump or a black frame.
 */
export function clampAfterShrink(
  currentIndex: number,
  adsLength: number,
): number {
  const len = safeNonNegativeInt(adsLength);
  if (len <= 0) return 0;
  const i = safeNonNegativeInt(currentIndex);
  if (i >= len) return 0;
  return i;
}

/**
 * Resolve the slot the player should currently render. Combines a
 * `% adsLength` guard (modulo against transient out-of-range states
 * between effect firings) with a sentinel for the empty case.
 *
 * Returns `NO_AD_INDEX` (-1) if the playlist is empty so the caller can
 * branch declaratively (`safeIndex === NO_AD_INDEX` ⇒ render the splash)
 * rather than guessing whether `0` means "first ad" or "no ad".
 *
 * The bare `currentIndex % adsLength` form would coerce `0` to `0` for
 * both "empty playlist" and "first ad of a non-empty playlist", which
 * is a long-standing source of off-by-one bugs in carousel widgets.
 */
export function safeIndex(currentIndex: number, adsLength: number): number {
  const len = safeNonNegativeInt(adsLength);
  if (len <= 0) return NO_AD_INDEX;
  const i = safeNonNegativeInt(currentIndex);
  return i % len;
}

/**
 * Materialise the next [steps] selections starting at [startIndex].
 * Pure helper — useful for:
 *   - the demo status overlay (preview the next 3 ads in the rotation),
 *   - unit tests that pin wrap-around behaviour for arbitrary lengths,
 *   - operator log lines (`"upcoming: A → B → C → A"`).
 *
 * Bounded length: callers should pass small `steps` (≤ 32). Returns
 * `[]` for empty playlists. The first element is `safeIndex(startIndex,
 * adsLength)` so the trace lines up with what the player is currently
 * showing.
 */
export function simulateRotation(
  startIndex: number,
  adsLength: number,
  steps: number,
): number[] {
  const len = safeNonNegativeInt(adsLength);
  if (len <= 0) return [];
  const stepCount = safeNonNegativeInt(steps);
  if (stepCount <= 0) return [];
  const out: number[] = new Array(stepCount);
  let i = safeIndex(startIndex, len);
  if (i === NO_AD_INDEX) return [];
  for (let k = 0; k < stepCount; k += 1) {
    out[k] = i;
    i = (i + 1) % len;
  }
  return out;
}

/**
 * Internal exports for the node:test suite. Not part of the public API
 * — consumers should not import from this object. We expose it so the
 * test file can pin the integer-coercion edge cases without leaking
 * `safeNonNegativeInt` into the module's general-purpose surface.
 */
export const __test__ = {
  safeNonNegativeInt,
};
