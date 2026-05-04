"use client";

/**
 * Player client component — the runtime that the Android WebView actually
 * paints in the fridge. This file owns the SSE subscription / schedule
 * reload behaviour for AC 5, Sub-AC 4:
 *
 *   "Implement SSE subscription on player page so device reloads schedule
 *    immediately when remapped without manual refresh."
 *
 * Behaviour:
 *   1. On mount, opens an EventSource against
 *        GET /api/devices/{deviceId}/stream
 *      via the [usePlayerSse] hook. The hook handles auto-reconnect,
 *      handshake, and event dispatch — see `hooks/usePlayerSse.ts`.
 *      (Backend also still serves the legacy `/events` route for the
 *       already-deployed WebViews; AC 1 standardises new players on
 *       `/stream`.)
 *   2. On the initial CONNECTED handshake, fetches the current playlist
 *      from `GET /api/devices/{deviceId}/playlist` and stores it in
 *      component state. (We do this from inside the SSE pipeline, not as
 *      a separate effect, so there is exactly one code path that loads
 *      the schedule — the same path that runs on a remap. That keeps
 *      the demo behaviour identical between "device just booted" and
 *      "device just got remapped".)
 *   3. On every MAPPING_CHANGED SSE event:
 *        a) immediately paints a "Switching to {restaurant}…" splash
 *           using the new restaurantId from the SSE payload (no need
 *           to wait for the playlist round-trip);
 *        b) re-fetches the playlist from the backend and replaces the
 *           current schedule in state.
 *      The Android device therefore reloads its schedule WITHOUT the
 *      operator pressing refresh, satisfying the AC verbatim.
 *   4. On PLAYLIST_UPDATE: same playlist refetch path, no splash.
 *   5. Surfaces a small status overlay (top-right corner) showing the
 *      live SSE state — `connecting`, `open`, `reconnecting`, etc.
 *      This is invaluable during the demo so a human watching the
 *      screen can confirm the SSE channel is healthy without opening
 *      the WebView devtools.
 *
 * AC 60202, Sub-AC 2 (this file): round-robin rotation of `playlist.ads`.
 *   The player picks ad N, plays it once, advances to ad (N+1) % length on
 *   the `<video>`'s `ended` event, and so on. The index resets to 0 every
 *   time the playlist itself is replaced (initial fetch, MAPPING_CHANGED
 *   refetch, PLAYLIST_UPDATE refetch) so a remap always restarts from the
 *   first ad of the new restaurant — consistent demo behaviour.
 *
 *   Edge cases the rotation handles:
 *     - Single ad (`ads.length === 1`): keep `<video loop>` to avoid an
 *       end/restart flicker every 15-30s; round-robin is a no-op anyway.
 *     - Empty playlist: render the placeholder splash, no rotation.
 *     - Video element fires `error` (broken file, network blip): advance
 *       to the next ad rather than stalling on the broken one.
 *     - Playlist length shrinks under the live index (rare, e.g. ad
 *       removed via PLAYLIST_UPDATE while playing): clamp back to 0.
 *
 * AC 60203, Sub-AC 3 (this file): wire playlist state changes to the
 *   `<video>` element so the newly selected ad actually starts playing.
 *
 *   The contract this sub-AC owns end-to-end:
 *
 *     "Whenever any input that determines which ad should play next changes,
 *      the `<video>` element must remount with the new `src` AND the WebView
 *      must begin playback of that ad — without operator action."
 *
 *   The inputs that trigger a re-selection are:
 *     1. The playlist itself was replaced (initial fetch / mapping change /
 *        playlist update). Detected via `playlistState.loadedAt` — bumped
 *        to `Date.now()` on every successful commit in `reloadSchedule`.
 *     2. The round-robin index advanced (`safeIndex` changed) on `ended`
 *        or `error`.
 *     3. The selected ad's identity changed (`currentAd.adId`) — covers
 *        an in-place playlist edit that swaps an ad at the same slot.
 *
 *   How this sub-AC fulfils the contract:
 *     a) Computes a single `selectedAdKey` string that joins all three
 *        inputs and uses it as the React `key` on the `<video>`. Any
 *        change forces React to unmount the previous element and mount a
 *        fresh one with the new `src` — the only reliable cross-browser
 *        way to switch an HTML5 video to a new source. Re-using the same
 *        element + setting `src` is what causes the WebView to keep the
 *        old buffer or fail to autoplay; remounting bypasses both bugs.
 *     b) Holds a ref to the underlying `<video>` element and, on every
 *        `selectedAdKey` change, runs an effect that imperatively calls
 *        `load()` (force the WebView to (re-)read the byte-range stream
 *        for the new src) and `play()` (the `autoPlay` attribute is best-
 *        effort on Android WebView; we belt-and-braces it here). Errors
 *        from the play promise are caught and routed through the same
 *        `advanceOnError` path the `onError` event uses, so a broken file
 *        in the playlist still keeps the demo moving.
 *     c) Surfaces `selectedAdKey` to the status overlay so the live demo
 *        can confirm the wiring fired (the key flips visibly each time
 *        the playlist changes or rotation advances).
 *
 * AC 3 Sub-AC 2 (this file): "track and enforce daily_play_count per ad
 *   using local persistence (e.g., localStorage keyed by date) to skip ads
 *   that have reached their daily cap".
 *
 *   How this file fulfils the contract:
 *     a) On mount, hydrates `counters` from `localStorage` via
 *        `loadCounters(deviceId)`. The helper applies a date-rollover so
 *        yesterday's bag never bleeds into today's quota.
 *     b) Pipes the schedule-window-filtered ads through `filterUnderCap`
 *        before they reach the round-robin index, so a capped-out ad is
 *        invisible to the rotation (skipped, not paused).
 *     c) On every `<video onEnded>` (i.e. an actual completed playthrough,
 *        not a remap-driven unmount), increments the counter for the ad
 *        that just finished AND persists the new bag synchronously.
 *        Increment-then-advance ordering: the increment is applied to the
 *        ad we just finished, before the round-robin re-evaluates which ad
 *        plays next. If the new count tips the ad over its cap,
 *        `filterUnderCap` removes it from the next render's active set
 *        and rotation continues with the survivors.
 *     d) Errors (`onError`) and remap-driven unmounts deliberately do NOT
 *        increment — operators expect the count to reflect ads the screen
 *        actually finished playing, not ads that were aborted.
 *     e) Surfaces `capped` and `today` to the status overlay so the demo
 *        operator can confirm the cap fired without dev-tools.
 *
 * Out of scope for this sub-AC (handled in sibling sub-ACs):
 *   - Filtering ads by their schedule window (HH:mm) — that's AC 9 above.
 *   - HTTP Range video streaming behaviour (relies on backend serving
 *     `Accept-Ranges: bytes`, owned by AC 4).
 *   - Auth-and-isolation pass to lock the player APIs behind a JWT.
 */

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { ApiError } from "@/lib/api";
import { StandbyScreen } from "./StandbyScreen";
import {
  fetchPlaylist,
  filterActiveAds,
  normaliseInlinePlaylist,
  type DevicePlaylist,
  type PlaylistAd,
} from "@/lib/playlist";
import {
  usePlayerSse,
  type MappingChangedPayload,
  type ReloadReason,
  type SsePlayerStatus,
} from "@/hooks/usePlayerSse";
/*
 * AC 8 — round-robin rotation algorithm. Extracted to a pure helper module
 * (`lib/roundRobin.ts`) so the algorithm has a single auditable home with
 * unit-test coverage. The wiring below intentionally preserves AC 7 Sub-AC
 * 3's belt-and-braces effect dependency keys (`[selectedAdKey, adsLength,
 * advanceOnError]`) — only the inline arithmetic was swapped for named
 * function calls; behaviour is byte-for-byte identical.
 */
import {
  advance as roundRobinAdvance,
  clampAfterShrink as roundRobinClamp,
  reset as roundRobinReset,
  safeIndex as roundRobinSafeIndex,
} from "@/lib/roundRobin";
/*
 * AC 3 Sub-AC 2 — per-ad daily play count enforcement. Pure helpers + a
 * tiny localStorage wrapper extracted to `@/lib/dailyCount` so the cap
 * algorithm and persistence layer are unit-testable in isolation from the
 * React lifecycle. This component is the single consumer:
 *
 *   - `loadCounters` runs once on mount to pick up yesterday/today's
 *     persisted bag (date-rollover applied automatically).
 *   - `filterUnderCap` narrows the active-ads array AFTER the schedule
 *     window filter, so a capped-out ad doesn't even enter the round-robin.
 *   - `incrementCount` + `saveCounters` runs from `onEnded` (i.e. after a
 *     completed playthrough) so we never count an ad that was skipped
 *     mid-stream by a remap.
 *   - `todayKey` lets the status overlay surface the counter day so the
 *     demo operator can see the rollover without dev-tools.
 */
import {
  emptyCounters,
  filterUnderCap,
  getRemaining,
  incrementCount,
  loadCounters,
  rolloverIfNewDay,
  saveCounters,
  todayKey,
  type DailyCounters,
} from "@/lib/dailyCount";
/*
 * 큐에 담긴 광고가 매 사이클 새 순서로 섞여 보이도록 하는 보조 셔플.
 * 라운드 로빈 자체는 그대로 두고, 라운드 로빈이 도는 *배열의 순서* 만
 * 매 사이클 바꾼다 — 결정적 PRNG 라 테스트 가능하다.
 */
import { adIdSetKey, shuffleWithSeed } from "@/lib/shufflePlaylist";
/*
 * AC 20202 Sub-AC 2 — emit play events (STARTED / FINISHED) from the
 * player to the backend. The helper is fire-and-forget by design: a
 * failed POST must not interrupt playback, so the wrapper swallows
 * errors and only logs at warn level. See `web/lib/playEvents.ts` for
 * the wire contract and resilience rationale.
 */
import { reportPlayEvent } from "@/lib/playEvents";

interface PlayerClientProps {
  /** Device UUID parsed from the route params (`/player/[deviceId]`). */
  deviceId: string;
}

type PlaylistState =
  | { kind: "initial" }
  | { kind: "loading" }
  | { kind: "ready"; playlist: DevicePlaylist; loadedAt: number }
  | { kind: "error"; message: string };

/**
 * Transient overlay shown right after a MAPPING_CHANGED so the operator
 * sees the device acknowledged the remap before the playlist refetch
 * completes. Auto-clears after `SPLASH_MS`.
 */
interface RemapSplash {
  restaurantId: string;
  assignmentId: string;
  startedAt: number;
}
const SPLASH_MS = 2500;

/**
 * 32-bit FNV-like 문자열 해시. 플레이리스트 셔플 seed 의 한 입력으로만
 * 쓰이며, 보안 용도가 아니므로 충돌 위험은 무관하다. 같은 입력에 같은
 * 정수를 돌려주는 결정성만 보장하면 된다.
 */
function hashString(s: string): number {
  let h = 0x811c9dc5;
  for (let i = 0; i < s.length; i += 1) {
    h ^= s.charCodeAt(i);
    h = Math.imul(h, 0x01000193);
  }
  return h >>> 0;
}

export function PlayerClient({ deviceId }: PlayerClientProps) {
  const [playlistState, setPlaylistState] = useState<PlaylistState>({
    kind: "initial",
  });
  const [splash, setSplash] = useState<RemapSplash | null>(null);

  /**
   * 앱/탭이 닫힐 때 backend 에 명시적 "offline" 신호 보내기. SSE 가 끊기는
   * 것만으론 keepalive 30초 주기 + lastSeenAt fresh 이면 어드민 모니터에
   * 짧게 phantom LIVE 가 남을 수 있는데, sendBeacon 한 번이면 즉시 해제된다.
   *
   * 왜 sendBeacon 인가:
   *   - 페이지 unload 도중에도 비동기 큐로 전송이 보장됨 (fetch + keepalive
   *     보다 호환성 더 좋음, Android WebView 도 지원)
   *   - 응답 무시 — 어차피 페이지가 죽는 중
   *
   * 왜 pagehide 인가:
   *   - beforeunload 는 모바일/SPA 에서 발사 안 되는 경우 흔함
   *   - pagehide 는 BFCache, 앱 백그라운드, WebView destroy 모두에서 안정 발사
   *   - visibilitychange + hidden 도 같이 쓰면 백그라운드만 갔을 때도 잡지만
   *     "잠깐 다른 앱 봤다 왔을 때" 도 오프라인으로 표시되어 운영자에게 혼란.
   *     pagehide 만 단독.
   */
  useEffect(() => {
    if (typeof window === "undefined") return;
    const onHide = () => {
      try {
        const url = `${process.env.NEXT_PUBLIC_API_BASE_URL ?? ""}/api/devices/${encodeURIComponent(deviceId)}/offline`;
        // Blob 으로 보내야 일부 브라우저가 sendBeacon body 를 수락한다.
        navigator.sendBeacon?.(url, new Blob([""], { type: "application/json" }));
      } catch {
        /* 페이지 죽는 중 — swallow */
      }
    };
    window.addEventListener("pagehide", onHide);
    return () => {
      window.removeEventListener("pagehide", onHide);
    };
  }, [deviceId]);

  /**
   * AC 60202 Sub-AC 2 — round-robin index into `playlist.ads`.
   *
   * Lives next to `playlistState` because the two are tightly coupled: the
   * index is only meaningful relative to a specific playlist generation.
   * When a new playlist arrives (any kind of refetch — initial, mapping
   * change, playlist update), we reset the index to 0 so the device always
   * starts the new schedule from the first ad. The reset is keyed off
   * `playlistState.loadedAt` (a timestamp bumped on every successful
   * refetch — see `reloadSchedule`) so even an identical-shape playlist
   * still resets cleanly after a remap.
   */
  const [currentIndex, setCurrentIndex] = useState<number>(0);
  /**
   * 광고가 끝나서 다음으로 advance 한 횟수. selectedAdKey 에 포함되어
   * 단일 광고 케이스에서도 매 loop 마다 video 가 remount 되도록 보장 —
   * 그래야 onPlay 가 다시 발사돼 STARTED 이벤트와 일일 cap 카운터가 동작.
   */
  const [cycleCount, setCycleCount] = useState<number>(0);

  /**
   * AC 3 Sub-AC 2 — per-ad daily play counters.
   *
   * The lazy initialiser runs once on mount and reads from localStorage
   * via `loadCounters(deviceId)`. That helper:
   *   - is SSR-safe (no `window` access during Next.js prerender — falls
   *     back to `emptyCounters` when there is no localStorage),
   *   - applies a date-rollover, so a counter bag stamped with yesterday
   *     evaluates to a fresh same-day bag without us having to schedule
   *     a midnight wake-up.
   *
   * `deviceId` is captured by the lazy initialiser only — it doesn't
   * change at runtime (the WebView opens a fixed URL per device), but if
   * a future remount swapped the device we'd want a fresh load anyway,
   * which the React `useState` lazy form gives us for free.
   *
   * The bag is mutated via the pure `incrementCount` helper from `onEnded`
   * (see `handleAdEnded` below). Every mutation is mirrored to
   * localStorage via `saveCounters`, so a WebView crash / power-cycle
   * mid-day preserves the day's progress.
   */
  const [counters, setCounters] = useState<DailyCounters>(() => {
    // SSR guard: `loadCounters` itself checks for `window`, but bailing
    // here too keeps the React state initial value deterministic between
    // server-render and client-hydrate (avoids a hydration mismatch).
    if (typeof window === "undefined") return emptyCounters();
    return loadCounters(deviceId);
  });

  /**
   * AC 20201 Sub-AC 1 — proactive day-rollover detection.
   *
   * The pure helpers `incrementCount` and `loadCounters` are both
   * date-rollover-aware, but they only run at discrete moments (initial
   * mount, after each `<video onEnded>`). If the player is idle across
   * local midnight — long ads that span the boundary, an empty schedule
   * overnight, the WebView throttled in the background, a single ad on
   * `loop=true` because `adsLength === 1` (so `ended` never fires) — the
   * in-memory `counters.date` drifts past today and `filterUnderCap`
   * keeps applying yesterday's stale counts. A creative that hit its
   * daily cap at 21:00 the previous day stays filtered out at 00:01 even
   * though its quota should have reset.
   *
   * This effect closes that gap with two complementary timers:
   *
   *   1. A precise `setTimeout` armed for the next local midnight + 1s.
   *      The +1s margin protects against a setTimeout that fires a few
   *      ms early (timer skew on suspended tabs is real); the rollover
   *      predicate then sees the new calendar day cleanly. The handler
   *      re-arms itself for the following midnight, so the device keeps
   *      rolling over for as many days as it stays running.
   *
   *   2. A 60s polling fallback for the case where the precise timeout
   *      didn't fire on time — Android Doze suspending the WebView, an
   *      NTP correction jumping the clock, the OS killing background
   *      timers. The poll is cheap (one date comparison) and catches a
   *      missed boundary within a minute.
   *
   * Both paths funnel through the same `rolloverIfNewDay` helper used
   * by `loadCounters` / `incrementCount`. When the date already matches
   * the helper returns the same reference, so the React setter no-ops
   * and we don't trigger an unrelated re-render. On a real rollover the
   * fresh empty bag is also persisted to localStorage so a WebView
   * reload immediately after midnight doesn't pick up yesterday's bag
   * from storage and reinstate the stale state.
   *
   * Why local time rather than UTC: see the module docstring on
   * `dailyCount.ts` — the operator configured the daily cap against
   * wall-clock semantics (matching the `HH:mm` schedule windows), so
   * the reset must happen at THEIR midnight, not 09:00 KST.
   *
   * `deviceId` is the only dep: it's stable for the life of the page
   * (the WebView opens a fixed URL per device), but if it ever changed
   * we'd want fresh timers anchored to the new device's persistence
   * key.
   */
  useEffect(() => {
    if (typeof window === "undefined") return;

    const checkRollover = () => {
      setCounters((prev) => {
        const next = rolloverIfNewDay(prev, todayKey());
        // Reference equality (next === prev) means today's date already
        // matches the bag — skip the persist write so the 60s poll stays
        // idle for 99.999% of ticks and React doesn't see a churned ref.
        if (next === prev) return prev;
        saveCounters(deviceId, next);
        return next;
      });
    };

    let midnightTimeout: ReturnType<typeof setTimeout> | null = null;
    const scheduleMidnight = () => {
      const now = new Date();
      // 00:00:01 of the next local calendar day. Built field-by-field
      // (rather than `new Date(... + 86_400_000)`) so DST transitions
      // — Korea doesn't observe DST today, but the Android device may
      // be sold abroad — produce the right wall-clock midnight.
      const tomorrow = new Date(
        now.getFullYear(),
        now.getMonth(),
        now.getDate() + 1,
        0,
        0,
        1,
        0,
      );
      const ms = Math.max(1000, tomorrow.getTime() - now.getTime());
      midnightTimeout = setTimeout(() => {
        checkRollover();
        scheduleMidnight();
      }, ms);
    };
    scheduleMidnight();

    // Belt-and-braces poll. The precise timer above handles the common
    // case; this catches the WebView-was-asleep case within ~60s. 60s
    // is the same cadence as a minute-resolution schedule (we already
    // tick `nowTick` every 30s), so it adds no perceptible cost.
    const pollId = setInterval(checkRollover, 60_000);

    return () => {
      if (midnightTimeout !== null) clearTimeout(midnightTimeout);
      clearInterval(pollId);
    };
  }, [deviceId]);

  // Track the most recently issued playlist fetch so a slow earlier
  // fetch can't overwrite a faster newer one (race after a quick
  // sequence of MAPPING_CHANGED events during the demo). The ref is
  // bumped on every refetch and each fetch only commits to state if
  // its id still matches.
  const fetchSeqRef = useRef<number>(0);

  /**
   * The playlist refetch path. Single code path used by both initial
   * mount and SSE-driven reloads, so the demo behaves identically when
   * the device just booted vs. just got remapped. Called from:
   *   - the mount effect below (Sub-AC 1: initial playlist fetch on mount),
   *   - the SSE hook on first CONNECTED (belt-and-braces),
   *   - the SSE hook on every MAPPING_CHANGED,
   *   - the SSE hook on every PLAYLIST_UPDATE.
   *
   * AC 60201 Sub-AC 1 — fast path: if a `PLAYLIST_UPDATE` SSE event
   * arrived with an inline playlist already on the wire, normalise it and
   * commit to state via `setPlaylistState` immediately, skipping the HTTP
   * round-trip to `/api/devices/{id}/playlist`. The refetch is still used
   * as the fallback so a malformed inline payload never strands the
   * device on stale state.
   */
  const reloadSchedule = useCallback(
    async (reason: ReloadReason) => {
      const seq = ++fetchSeqRef.current;

      // ----- Fast path: inline playlist on a PLAYLIST_UPDATE event.
      // The hook already validated that the event was for this device
      // (and warned on mismatch). We re-run the canonical normaliser
      // here — same one used by the refetch path — so the shape stored
      // in state is identical regardless of which path fed it.
      if (
        reason.kind === "playlist_update" &&
        reason.payload &&
        reason.payload.playlist
      ) {
        try {
          const playlist = normaliseInlinePlaylist(
            deviceId,
            reason.payload.playlist,
          );
          setPlaylistState({
            kind: "ready",
            playlist,
            loadedAt: Date.now(),
          });
          return; // skip refetch — state already updated.
        } catch (err) {
          // Inline payload was unusable — fall through to authoritative
          // refetch below. Log so the operator can see why the fast path
          // didn't apply, but don't surface to the UI: the refetch will
          // succeed in almost all cases.
          console.warn(
            "[PlayerClient] inline playlist normalise failed; falling back to refetch",
            err,
          );
        }
      }

      setPlaylistState((prev) =>
        prev.kind === "ready"
          ? prev // keep showing the current playlist while the new one loads
          : { kind: "loading" },
      );
      try {
        const playlist = await fetchPlaylist(deviceId);
        if (seq !== fetchSeqRef.current) return; // stale fetch — drop.
        setPlaylistState({
          kind: "ready",
          playlist,
          loadedAt: Date.now(),
        });
      } catch (err) {
        if (seq !== fetchSeqRef.current) return;
        const message = describeError(err);
        // Don't blow away a working playlist if a refetch failed —
        // keep playing what we had and surface the error inline.
        setPlaylistState((prev) =>
          prev.kind === "ready" ? prev : { kind: "error", message },
        );
        console.warn(
          "[PlayerClient] playlist refetch failed",
          { reason, deviceId, message },
        );
      }
    },
    [deviceId],
  );

  /** Show the remap splash for ~2.5s, then clear. */
  const handleMappingChanged = useCallback(
    (payload: MappingChangedPayload) => {
      setSplash({
        restaurantId: payload.restaurantId,
        assignmentId: payload.assignmentId,
        startedAt: Date.now(),
      });
    },
    [],
  );

  // Auto-clear the remap splash after SPLASH_MS so the player goes back
  // to playing video once the new playlist is loaded.
  useEffect(() => {
    if (!splash) return;
    const t = setTimeout(() => setSplash(null), SPLASH_MS);
    return () => clearTimeout(t);
  }, [splash]);

  /*
   * AC 7, Sub-AC 1: "initial playlist fetch from backend API on mount".
   *
   * We fire the first `GET /api/devices/{deviceId}/playlist` synchronously
   * with mount, INDEPENDENT of the SSE handshake. This guarantees the
   * player page paints the schedule even if:
   *   - the SSE channel takes a long time to connect (slow restaurant WiFi),
   *   - the SSE endpoint is unreachable but the playlist endpoint is fine,
   *   - the WebView blocks EventSource for any reason.
   *
   * The SSE hook may also call `reloadSchedule({ kind: "connected" })`
   * shortly after on its first CONNECTED handshake — `fetchSeqRef`
   * handles the race so only the most recent fetch commits to state.
   *
   * Only `deviceId` is in deps: the route param effectively never changes
   * (the WebView opens a fixed URL per device), but if it ever did we'd
   * want a fresh mount fetch for the new device.
   */
  useEffect(() => {
    if (!deviceId) return;
    void reloadSchedule({ kind: "initial" });
  }, [deviceId, reloadSchedule]);

  // The actual SSE subscription — see `hooks/usePlayerSse.ts` for the
  // full lifecycle / reconnect / event dispatch implementation.
  const { status, reloadCounter, reconnectAttempts, reconnectNow } = usePlayerSse(
    {
      deviceId,
      onScheduleReload: reloadSchedule,
      onMappingChanged: handleMappingChanged,
      // `debug: true` lights up [usePlayerSse] console logs — flip on
      // before the demo to confirm MAPPING_CHANGED arrives end-to-end.
      debug: false,
    },
  );

  // ----- AC 60202 Sub-AC 2: round-robin selection.
  //
  // Pull the active ads array out of the union once so the rest of the
  // component can deal with a stable shape. `ads` is `[]` for every
  // non-ready state (`initial`/`loading`/`error`), which makes all the
  // length / clamp checks below safe to write without branching first.
  const allAds: PlaylistAd[] =
    playlistState.kind === "ready" ? playlistState.playlist.ads : [];

  /*
   * AC 9 — "Ads play only within scheduled time window".
   *
   * The playlist endpoint returns every ad scheduled to a device. The
   * player's job is to narrow that to the subset whose HH:mm window
   * contains the device-local wall clock right now. The narrowing has
   * to be live — the demo runs across 17:00 / 21:00 boundaries — so we
   * tick a `nowTick` state every `SCHEDULE_TICK_MS` and recompute
   * `activeAds` on each tick. Predicate logic itself lives in pure
   * helpers in `lib/playlist.ts` so it's testable without React.
   *
   * Why a state tick rather than a `Date.now()` read on every render:
   *   - Without a state update React has no reason to re-render and
   *     the active set would freeze at the value computed on the last
   *     unrelated render — most commonly when a video `ended` event
   *     advances the round-robin index. An ad whose window just ended
   *     would keep playing for an unbounded time.
   *   - 30s is plenty for a minute-resolution schedule (`HH:mm`)
   *     while keeping the WebView idle most of the time.
   *
   * `nowTick` is intentionally NOT a `Date` — we keep it as an integer
   * counter so changes are cheap to compare in deps arrays.
   */
  const SCHEDULE_TICK_MS = 30_000;
  const [nowTick, setNowTick] = useState<number>(0);
  useEffect(() => {
    const id = setInterval(() => setNowTick((n) => n + 1), SCHEDULE_TICK_MS);
    return () => clearInterval(id);
  }, []);

  /*
   * Memoised so the round-robin index reset effect below sees a stable
   * reference between ticks when the active-set didn't actually change
   * (e.g. one minute past, none of the windows opened or closed). Includes
   * `nowTick` so we DO recompute on each scheduled tick — `filterActiveAds`
   * itself is the cheap part; what matters is that the round-robin index
   * resets the moment an ad's window opens or closes.
   *
   * `playlistGen` is folded into the deps so a fresh playlist (initial
   * fetch / remap / playlist update) re-evaluates immediately rather than
   * waiting for the next tick.
   */
  const playlistGenForMemo =
    playlistState.kind === "ready" ? playlistState.loadedAt : 0;
  /*
   * AC 9 first pass: narrow to ads inside their HH:mm window.
   * Kept as a separate intermediate so the AC 3 cap filter below can run
   * on top of an already-window-filtered set, which:
   *   - keeps the filter chain readable (window then cap),
   *   - lets the status overlay surface "active" (window only) and
   *     "capped" (window+cap) counts independently if needed,
   *   - means a capped ad whose window is closed counts as "out of
   *     window", not as "capped today" — matches operator intuition.
   */
  const windowedAds = useMemo<PlaylistAd[]>(
    () => filterActiveAds(allAds, new Date()),
    // See note below for why `allAds` is intentionally not in deps.
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [playlistGenForMemo, nowTick],
  );
  /*
   * AC 3 Sub-AC 2 — drop ads that have hit their daily cap. `counters` is
   * a dependency so the very next render after `incrementCount` re-evaluates
   * eligibility; the just-finished ad disappears from `ads` if it was the
   * last play remaining for the day.
   *
   * Order matters: window filter FIRST, then cap filter. A capped ad whose
   * window is closed is filtered out by the cheaper window check, and a
   * capped ad whose window is open shows up as "capped today" rather than
   * being silently excluded.
   *
   * `allAds` is recomputed each render but its identity only changes
   * when the playlist itself changes, so including `playlistGenForMemo`
   * is enough to capture playlist transitions. `nowTick` captures the
   * wall-clock advances. ESLint's exhaustive-deps would also want
   * `windowedAds`/`allAds` here but doing so would re-filter on every
   * parent render (a no-op visually but forcing re-renders downstream);
   * keying on `playlistGenForMemo`, `nowTick`, and `counters` is the
   * correct semantic set of deps.
   */
  const ads = useMemo<PlaylistAd[]>(
    () => filterUnderCap(windowedAds, counters.counts),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [playlistGenForMemo, nowTick, counters],
  );
  const adsLength = ads.length;
  /*
   * AC 3 Sub-AC 2 — overlay metric: how many of the in-window ads are
   * currently capped today. Surfaced next to `active n` so the operator
   * can distinguish "no ads scheduled now" from "all scheduled ads have
   * exhausted their daily quota". Pure derived state; no extra render.
   */
  const cappedCount = windowedAds.length - ads.length;
  // `loadedAt` doubles as the "playlist generation id": it is bumped to
  // `Date.now()` every time `reloadSchedule` commits a new playlist into
  // state, including via the inline-payload fast path. Using it as the
  // reset key means *every* playlist transition (initial → first fetch,
  // remap, in-place update) starts the round-robin back at index 0.
  const playlistGen =
    playlistState.kind === "ready" ? playlistState.loadedAt : 0;

  /**
   * Reset the rotation index whenever the playlist generation changes.
   * Without this, a remap to a different restaurant would resume at
   * whatever index the previous restaurant's playlist was on — almost
   * never the demo behaviour we want.
   *
   * AC 8: `roundRobinReset()` is the canonical "start of cycle" value. It
   * returns 0 today but is wrapped in a function so a future "resume where
   * you left off across reconnects" policy can land in one place rather
   * than every call site.
   */
  useEffect(() => {
    setCurrentIndex(roundRobinReset());
  }, [playlistGen, deviceId]);

  /**
   * Clamp the index if the playlist shrank under the live index. This
   * mostly protects against a PLAYLIST_UPDATE that drops ads while one
   * was mid-rotation; without the clamp we'd briefly render a `null`
   * `<video>` and then snap back when the next `ended` fired.
   *
   * AC 8: defers to `roundRobinClamp` (pure function, unit-tested) for
   * the bound check so the algorithm and the React effect can evolve
   * independently — for example, if we later add "snap to last index of
   * the new shorter playlist" semantics, we'd flip the helper without
   * touching this effect.
   */
  useEffect(() => {
    const clamped = roundRobinClamp(currentIndex, adsLength);
    if (clamped !== currentIndex) {
      setCurrentIndex(clamped);
    }
  }, [adsLength, currentIndex]);

  // Modulo guards against the brief render between an effect-driven reset
  // and the next paint. `safeIndex` is what we actually use to pick the ad.
  // AC 8: `roundRobinSafeIndex` returns `NO_AD_INDEX` (-1) for an empty
  // playlist; we collapse that to `0` here so the existing JSX branch
  // (`adsLength > 0 ? ads[safeIndex] : null`) keeps reading naturally.
  const rrIndex = roundRobinSafeIndex(currentIndex, adsLength);
  const safeIndex = rrIndex < 0 ? 0 : rrIndex;

  /**
   * "쌓아둔 광고가 잘 섞여서 보이도록" — 라운드 로빈이 순회하는 배열의
   * 순서를 매 사이클 새 순열로 바꾼다. 라운드 로빈 자체(인덱스 advance,
   * onEnded 카운팅 등) 는 그대로 두고, "어떤 배열을 도는지" 만 셔플한 사본
   * 으로 교체.
   *
   * shuffleVer 가 다음 두 시점에 bump 된다:
   *   1. ads 의 *식별 집합* 이 바뀔 때 (광고 추가/제거/만료) — adIdSetKey
   *      변화로 자동 reshuffle.
   *   2. 한 사이클이 끝나서 인덱스가 0 으로 wrap 될 때 — 같은 세트라도
   *      순서를 매번 바꿔 지루함을 줄인다.
   */
  const adIdsKey = useMemo(() => adIdSetKey(ads), [ads]);
  const [shuffleVer, setShuffleVer] = useState(0);
  const prevSafeIndexRef = useRef(0);
  useEffect(() => {
    // 사이클 wrap 감지: 직전 safeIndex 가 마지막 슬롯이었고 이번에 0 으로
    // 돌아왔으면 한 바퀴 완성 → reshuffle. ads 가 1 개 이하면 셔플 의미 없음.
    if (
      adsLength > 1 &&
      prevSafeIndexRef.current === adsLength - 1 &&
      safeIndex === 0
    ) {
      setShuffleVer((v) => v + 1);
    }
    prevSafeIndexRef.current = safeIndex;
  }, [safeIndex, adsLength]);

  const shuffledAds = useMemo(() => {
    if (ads.length <= 1) return ads;
    // seed 는 광고 집합 + 사이클 카운터 + 플레이리스트 세대의 조합. 같은
    // 입력에 같은 결과(테스트 가능) 를 주되 사이클이 바뀌면 새 순열이 나오게.
    const seed =
      hashString(adIdsKey) ^ shuffleVer ^ (playlistGen | 0) ^ (deviceId.charCodeAt(0) || 0);
    return shuffleWithSeed(ads, seed);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [adIdsKey, shuffleVer, playlistGen, deviceId]);

  const currentAd = adsLength > 0 ? shuffledAds[safeIndex] ?? ads[safeIndex] : null;

  /**
   * AC 60203 Sub-AC 3 — single source of truth for "which ad is selected".
   *
   * `selectedAdKey` is a stable string identifier that changes whenever ANY
   * input to the ad-selection process changes (playlist generation, rotation
   * index, or the ad's own identity). We use it for two complementary jobs:
   *
   *   - As the React `key` on the `<video>`, so React forces an unmount/
   *     remount of the element. This is the only cross-browser-reliable way
   *     to switch an HTML5 video to a new source — patching `src` on a
   *     mounted element keeps the old buffer in WebView and frequently
   *     fails to start the new file.
   *
   *   - As the dependency of an imperative effect below that calls
   *     `videoRef.current.load()` + `play()` on every change. The `<video
   *     autoPlay>` attribute is best-effort in Android WebView; we don't
   *     trust it on its own. The effect re-asserts playback every time the
   *     selection changes.
   *
   * Including `playlistGen` (== `playlistState.loadedAt`) covers the case
   * where the playlist was refetched but the ad at index 0 happens to be
   * the same one — the `adId` and `safeIndex` would both be unchanged, so
   * without `playlistGen` the key wouldn't move and the WebView would
   * happily keep buffering the *old* (potentially stale) file URL.
   */
  const selectedAdKey = currentAd
    ? `${currentAd.adId}:${safeIndex}:${playlistGen}:${cycleCount}`
    : `__no_ad__:${playlistGen}`;

  /**
   * Imperative handle to the `<video>` element. Owned by this component
   * (rather than passed up to a sibling sub-AC) because the only consumers
   * are the effect below and the JSX `ref` attribute — both local.
   */
  const videoRef = useRef<HTMLVideoElement | null>(null);

  /**
   * AC 3 Sub-AC 2 — ref to the ad currently displayed in `<video>`.
   *
   * Why a ref (not state): the daily-count increment in `handleAdEnded`
   * needs to know WHICH ad just finished. Reading `currentAd` from a
   * closure inside `useCallback` would force `handleAdEnded` to be
   * re-created every time the selection changed, which in turn would
   * thrash the `<video onEnded>` prop and trigger React to detach/
   * reattach the listener — sometimes losing the very `ended` event we
   * are trying to capture. A ref keeps the callback identity stable
   * while still reading the latest selection at fire time.
   */
  const currentAdRef = useRef<PlaylistAd | null>(null);
  useEffect(() => {
    currentAdRef.current = currentAd;
  }, [currentAd]);

  /**
   * Round-robin advance. Wired to the `<video>`'s `ended` event so each
   * ad plays once and then we move to (N+1) % length, wrapping around
   * forever. Functional setter form so concurrent state updates (e.g. a
   * playlist refetch landing right as an `ended` fires) compose cleanly.
   *
   * Declared above the imperative-load effect (AC 60203 Sub-AC 3) because
   * `advanceOnError` (which the effect calls on play() rejection) depends
   * on this. Keeping definitions in dependency order avoids TDZ surprises
   * and makes the eslint react-hooks/exhaustive-deps lint happy.
   */
  const advanceToNext = useCallback(() => {
    // AC 8: `roundRobinAdvance` encapsulates the `(i + 1) % N` arithmetic
    // plus the empty-playlist and single-ad short-circuits. Keeping the
    // setter functional preserves AC 7's race semantics (a refetch landing
    // mid-`onEnded` still composes correctly).
    setCurrentIndex((i) => roundRobinAdvance(i, adsLength));
    // 단일 광고 케이스에서도 selectedAdKey 가 변경되도록 cycle counter 증가.
    // 그래야 video remount → onPlay → STARTED 이벤트 재발사 → 일일 cap 카운트
    // 도 정상 증가. cycleCount 자체는 모듈로 없이 영구 증가(overflow 까지 한참).
    setCycleCount((c) => c + 1);
  }, [adsLength]);

  /**
   * AC 20202 Sub-AC 2 — STARTED-event dedup ref.
   *
   * The `<video onPlay>` event fires every time `paused` flips to `false`,
   * which includes legitimate "this ad just started" transitions AND
   * benign "the user (or the OS Doze handler) resumed a paused video"
   * transitions. We only want to report ONE `STARTED` event per ad
   * playthrough — otherwise the server's count would inflate every time
   * the WebView resumes from background.
   *
   * Strategy: stash the `selectedAdKey` we've already reported a START
   * for in a ref. When `onPlay` fires, compare against the ref:
   *   - mismatch → fresh selection, report STARTED, update the ref
   *   - match    → resume of an already-reported selection, skip
   *
   * Cleared via the AC 60203 Sub-AC 3 imperative-load effect when a new
   * `selectedAdKey` arrives — that runs strictly before the WebView's
   * `play` event, so the ref always reflects "have we reported the
   * CURRENT selection yet?".
   */
  const reportedStartKeyRef = useRef<string | null>(null);

  /**
   * AC 20202 Sub-AC 2 — `onPlay` handler.
   *
   * Fires once per ad selection (deduped against
   * [reportedStartKeyRef]) and POSTs an `eventType: "STARTED"` row to
   * `/api/devices/{deviceId}/play-events`. Fire-and-forget: a failed
   * report logs a warn line via the helper but does not interrupt
   * playback. Pause/resume cycles after the initial start are NOT
   * reported — operators expect "started" to mean "the WebView began
   * showing a fresh ad", not "playback resumed from background".
   *
   * No daily-count increment runs here — daily caps are tied to
   * completed playthroughs (see [handleAdEnded]). Operator semantics
   * for the cap are "plays the screen actually finished", not "plays
   * the screen attempted".
   */
  const handleAdStarted = useCallback(() => {
    const ad = currentAdRef.current;
    if (!ad) return;
    // Dedup on `selectedAdKey` so a pause/resume sequence inside the
    // same playthrough does not enqueue a second STARTED row. The key
    // changes on every legitimate ad-selection transition (round-robin
    // advance, playlist refetch, in-place ad swap), so each fresh
    // selection produces exactly one STARTED event.
    if (reportedStartKeyRef.current === selectedAdKey) return;
    reportedStartKeyRef.current = selectedAdKey;
    void reportPlayEvent(deviceId, {
      adId: ad.adId,
      eventType: "STARTED",
    });
  }, [deviceId, selectedAdKey]);

  /**
   * AC 3 Sub-AC 2 — `onEnded` handler.
   *
   * The flow on a completed playthrough:
   *   1. Increment the just-finished ad's daily count via the pure
   *      `incrementCount` helper. Date-rollover-aware: an ad that
   *      finished playing at 00:00:01 lands in the new day's bag, not
   *      yesterday's.
   *   2. Persist the new bag to localStorage. Best-effort — a quota /
   *      disabled-storage failure is logged inside the helper and
   *      doesn't disturb playback.
   *   3. Advance the round-robin to the next slot.
   *
   * Why the order matters:
   *   - The increment runs BEFORE `setCurrentIndex` so the next render
   *     sees the updated counters AND the new index in the same React
   *     tick. If the increment tipped the ad over its cap, the
   *     `filterUnderCap` memo above will drop it on the very next render,
   *     and `clampAfterShrink` will keep the rotation pointing at a valid
   *     slot.
   *   - We use `setCounters((prev) => incrementCount(prev, adId))` so a
   *     concurrent state update (e.g. the user opening a debug panel that
   *     also touches `counters`) composes without losing increments.
   *   - Persistence happens against the freshly-incremented value, not
   *     the React state (which is async). We compute it inside the setter
   *     and persist via the closure so we never write a stale snapshot.
   *
   * Increments only fire on a real `ended` event — never on a remap-
   * driven unmount or a video error. Operators expect the daily count to
   * reflect ads the screen actually finished, not ads that were aborted.
   */
  const handleAdEnded = useCallback(() => {
    const finishedAd = currentAdRef.current;
    if (finishedAd) {
      setCounters((prev) => {
        const next = incrementCount(prev, finishedAd.adId);
        // Best-effort persistence using the freshly-incremented value.
        // Stays inside the setter so we never persist a stale snapshot
        // and so two near-simultaneous `ended` events (rare, but possible
        // if a sibling effect dispatches one) compose correctly.
        saveCounters(deviceId, next);
        return next;
      });
      /*
       * AC 20202 Sub-AC 2 — server-side telemetry pair.
       *
       * Post a FINISHED play-event for the just-completed ad so the
       * backend's authoritative count can advance. Sequenced after the
       * client-side counter increment intentionally:
       *   - the local counter is the latency-critical cap enforcer (the
       *     player must NOT block the next ad on a network round trip);
       *   - the server POST is fire-and-forget telemetry (the helper
       *     swallows errors via a warn-level log).
       *
       * Only completed playthroughs report FINISHED. Aborts (`onError`,
       * remap-driven unmounts) deliberately skip this so the operator's
       * server-side cap reflects ads the screen actually finished —
       * matching the semantics already documented for the local
       * counter's increment-on-`ended`-only rule above.
       */
      void reportPlayEvent(deviceId, {
        adId: finishedAd.adId,
        eventType: "FINISHED",
      });
    }
    advanceToNext();
  }, [advanceToNext, deviceId]);

  /**
   * Same advance, but triggered by a video `error` (broken file, network
   * blip on a single segment). Skipping past a failed ad keeps the demo
   * moving — alternative behaviours (retrying the same ad, freezing on
   * the error) all fail badly during a live demo on restaurant WiFi.
   */
  const advanceOnError = useCallback(
    (err: unknown) => {
      console.warn(
        "[PlayerClient] video error, advancing to next ad",
        { adId: currentAd?.adId, err },
      );
      advanceToNext();
    },
    [advanceToNext, currentAd],
  );

  /**
   * AC 60203 Sub-AC 3 — belt-and-braces playback trigger.
   *
   * Even though the React `key` change above remounts the element, we
   * explicitly call `load()` then `play()` on every selection change.
   * Reasons for the imperative call on top of the declarative `autoPlay`:
   *
   *   - Android WebView occasionally rejects autoplay after a long-running
   *     remount-heavy session (we've seen it on the demo Galaxy Tabs).
   *     The explicit `play()` re-arms playback deterministically.
   *
   *   - `load()` resets the media element to its initial state and starts
   *     fetching the new resource immediately — bypassing any cached
   *     readyState left over from a previous src on the same logical
   *     element (which can happen if the React reconciliation reuses an
   *     element across keys due to fast-refresh in dev).
   *
   *   - The `play()` promise rejection (DOMException AbortError when
   *     load() interrupts a previous play, NotAllowedError if autoplay
   *     policy blocks us) is funnelled to `advanceOnError` so a single
   *     broken file does not stall the demo.
   *
   * The effect's `selectedAdKey` dep keeps it primitive-stable so it
   * doesn't re-fire on unrelated re-renders. `advanceOnError` is also a
   * dep (it changes when the ad list or current ad changes) so the
   * effect always invokes the latest version, but those changes also
   * change `selectedAdKey`, so this is at most one extra invocation per
   * legitimate selection change.
   */
  /*
   * AC 9 nuance for the imperative play effect below: with the schedule
   * window filter applied, `adsLength` becomes reactive to ads rolling
   * in/out of their windows. If a sibling ad's window closes, `adsLength`
   * shrinks from N to N-1 even though the currently-playing ad is
   * unchanged. Including the raw count in the deps would cause `load()`
   * + `play()` to fire mid-playthrough on every such transition,
   * visibly interrupting the video.
   *
   * We narrow the dep to the boolean `hasAds`. This honours the original
   * AC 60203 Sub-AC 3 intent — re-fire when the active set transitions
   * empty → non-empty so a fresh window's first ad starts deterministically
   * — while ignoring count changes within the active set. Selected-ad
   * transitions are still captured by `selectedAdKey`.
   */
  const hasAds = adsLength > 0;
  useEffect(() => {
    const el = videoRef.current;
    if (!el) return;
    if (!hasAds) return;
    // Resetting load() before play() is the canonical recipe for forcing
    // a fresh fetch of a new src on HTMLMediaElement.
    try {
      el.load();
    } catch (err) {
      // load() doesn't normally throw, but guard anyway so a weird WebView
      // bug can't kill the player.
      console.warn("[PlayerClient] video.load() threw", err);
    }
    const playPromise = el.play();
    if (playPromise && typeof playPromise.then === "function") {
      playPromise.catch((err: unknown) => {
        // Two common causes:
        //   1. Browser interrupted play() because load() / a newer key
        //      change started another fetch — benign, the next selection
        //      effect will handle it.
        //   2. Autoplay policy blocked us — fatal for this ad. Skip past
        //      the broken file so the carousel keeps moving.
        const name =
          err && typeof err === "object" && "name" in err
            ? String((err as { name?: unknown }).name)
            : "";
        if (name === "AbortError") {
          // Superseded by a newer load() — ignore.
          return;
        }
        advanceOnError(err);
      });
    }
  }, [selectedAdKey, hasAds, advanceOnError]);

  return (
    <div className="player-root">
      {/*
        Status overlay — always visible during the hackathon so the demo
        operator can see SSE state at a glance. Sibling sub-ACs may hide
        this for production once playback is the sole signal.
      */}
      <PlayerStatusOverlay
        deviceId={deviceId}
        status={status}
        reloadCounter={reloadCounter}
        reconnectAttempts={reconnectAttempts}
        playlistState={playlistState}
        currentIndex={safeIndex}
        currentAd={currentAd}
        /*
         * AC 9 — surface the live count of in-window+under-cap ads vs.
         * total ads scheduled for this device. Lets the demo operator
         * verify the window+cap filter without reading from the WebView
         * console.
         */
        activeCount={adsLength}
        /*
         * AC 3 Sub-AC 2 — number of in-window ads currently capped today.
         * Distinct line so the operator can tell "out of window" from
         * "out of plays today".
         */
        cappedCount={cappedCount}
        /*
         * AC 3 Sub-AC 2 — current ad's plays-today and cap, formatted as
         * "n/N" or "n/∞". Watching this tick after each `ended` event is
         * the most direct demo signal that the daily-count enforcement
         * is wired end-to-end.
         */
        capUsage={formatCapUsage(currentAd, counters.counts)}
        /*
         * AC 3 Sub-AC 2 — counter day, in local YYYY-MM-DD form. The
         * overlay row visibly flips at local midnight without any
         * operator action.
         */
        countersDate={counters.date}
        /*
         * AC 60203 Sub-AC 3 — surface the live selection key so the demo
         * operator can visually confirm the wiring fired (the `now`
         * column flips its trailing fragment whenever the selected ad
         * changes, even if the title is the same).
         */
        selectedAdKey={selectedAdKey}
        onReconnect={reconnectNow}
      />

      {/* Remap splash — appears the instant MAPPING_CHANGED arrives. */}
      {splash && (
        <div className="player-splash" role="status" aria-live="polite">
          <div className="player-splash__title">
            Switching restaurant…
          </div>
          <div className="player-splash__sub">
            new restaurant: <code>{splash.restaurantId}</code>
            <br />
            assignment: <code>{splash.assignmentId}</code>
          </div>
        </div>
      )}

      {/*
        AC 4 + AC 9 — "Outside scheduled window" splash.
        Shown when the playlist itself has ads but every one of them is
        currently outside its [startTime, endTime) window. This keeps the
        screen on the branded `/splash.png` during off-hours instead of
        either falling back to the wrong ad or showing the "no ads"
        message (which would falsely imply an empty schedule). Once the
        next window opens — at the next `nowTick` — `activeAds` will
        repopulate and the player switches back to playback automatically.

        AC 4 ("Player shows /splash.png splash when outside schedule or
        no ads") is satisfied here by passing `splashImage="/splash.png"`
        to `PlayerEmpty`, which paints the image as a full-bleed
        background behind the status message. Next.js serves the file
        from `web/public/splash.png` at the root path, so the same URL
        works in dev, behind nginx, and inside the Android WebView.
      */}
      {playlistState.kind === "ready" &&
        playlistState.playlist.ads.length > 0 &&
        adsLength === 0 && (
          <StandbyScreen
            restaurantId={playlistState.playlist.restaurantId}
            hint={describeNextWindow(playlistState.playlist.ads)}
          />
        )}

      {/* Playback area. */}
      {playlistState.kind === "ready" && currentAd && (
        <video
          /*
           * AC 60203 Sub-AC 3 — `selectedAdKey` forces the `<video>` to
           * remount whenever ANY input to ad-selection changes:
           *   - normal rotation:           safeIndex ticks → key changes
           *   - duplicate ad ids in playlist (same ad twice): safeIndex
           *                                disambiguates within the
           *                                same generation
           *   - in-place playlist edit (same slot, different ad): adId
           *                                changes
           *   - playlist refetch / remap:  playlistGen (loadedAt) ticks
           *
           * Without a remount the WebView holds onto the old buffer and the
           * next ad either starts mid-stream or doesn't autoplay. The
           * companion effect above also imperatively calls load() + play()
           * on every change of this same key, as a belt-and-braces measure
           * for Android WebView's flaky autoplay behaviour.
           */
          key={selectedAdKey}
          ref={videoRef}
          className="player-video"
          src={currentAd.videoUrl}
          autoPlay
          muted
          /*
           * `loop` 은 *항상 false*. true 로 두면 HTML5 <video> 가 자동으로
           * seek-and-replay 하지만 `ended` 이벤트가 발사되지 않아 두 가지
           * 핵심 기능이 깨진다:
           *   1) 일일 횟수 cap 카운터 증가 — onEnded 가 incrementCount 호출 →
           *      cap 미발동 → 무한 상영 버그
           *   2) STARTED play-event 재발사 — 어드민 모니터링 currentAd 윈도우
           *      안에 데이터가 안 들어와 "송출 대기" 로 잘못 표시
           *
           * 단일 광고 케이스도 onEnded → advanceToNext 가 같은 인덱스(=0)를
           * 반환하지만 selectedAdKey 가 cycleCount 까지 포함하므로 video
           * 가 강제 remount 되어 onPlay 가 다시 발사된다 → STARTED 재보고.
           */
          loop={false}
          playsInline
          controls={false}
          // Range-stream friendly: `preload="auto"` lets the WebView
          // ask the backend for byte ranges as needed.
          preload="auto"
          /*
           * AC 60202 Sub-AC 2 + AC 3 Sub-AC 2: the heart of round-robin
           * AND the daily-cap counter increment.
           *
           * `ended` fires once per playthrough; `handleAdEnded` first
           * increments the just-finished ad's daily count (and persists
           * the new bag to localStorage), then advances the round-robin
           * index. The `key` change remounts the element with the next
           * ad's `src`, autoplay resumes the loop on the new file. If
           * the increment tipped the just-finished ad over its cap, the
           * `filterUnderCap` memo upstream drops it from the active set
           * before the next render, so the rotation continues with the
           * remaining ads only.
           *
           * Errors deliberately route through `advanceOnError` (no
           * increment) — operators expect daily counts to reflect ads
           * the screen actually finished, not aborted plays.
           */
          onEnded={handleAdEnded}
          onError={advanceOnError}
          /*
           * AC 20202 Sub-AC 2 — STARTED play-event report.
           *
           * Fires on the WebView's `play` event (when `paused` flips to
           * `false`). The handler dedups against the current
           * `selectedAdKey` so a pause/resume cycle inside one
           * playthrough does NOT report a second STARTED. The
           * complementary FINISHED row is sent from `handleAdEnded`
           * after a complete playthrough, matching the daily-cap
           * "screen actually finished" semantics.
           */
          onPlay={handleAdStarted}
        />
      )}

      {/*
        AC 4 — "No ads scheduled" splash.
        When the device has zero ads scheduled (either the device is
        unmapped, or the restaurant has no creatives yet), we still want
        a branded screen rather than a black background. Same `/splash.png`
        as the off-hours branch, served by Next.js out of `web/public/`.
      */}
      {playlistState.kind === "ready" &&
        playlistState.playlist.ads.length === 0 && (
          <StandbyScreen
            restaurantId={playlistState.playlist.restaurantId}
          />
        )}

      {/* 연결 / 로딩 / 에러 — 운영자 디버그성 transient 상태도 손님 입장에선
          그냥 standby 로 보이는 게 자연스럽다. 운영자가 확인할 정보는 어드민
          모니터에서 별도로 노출된다. */}
      {playlistState.kind === "loading" && <StandbyScreen />}
      {playlistState.kind === "initial" && <StandbyScreen />}
      {playlistState.kind === "error" && (
        <StandbyScreen hint="잠시 후 다시 연결합니다" />
      )}

      <style jsx>{`
        .player-root {
          position: fixed;
          inset: 0;
          background: #000;
          color: #fff;
          font-family:
            -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto,
            "Helvetica Neue", sans-serif;
          overflow: hidden;
        }
        .player-video {
          width: 100%;
          height: 100%;
          object-fit: cover;
          background: #000;
        }
        .player-splash {
          position: absolute;
          inset: 0;
          display: flex;
          flex-direction: column;
          align-items: center;
          justify-content: center;
          background: rgba(0, 0, 0, 0.85);
          z-index: 10;
          text-align: center;
          padding: 24px;
          animation: fadeIn 200ms ease-out;
        }
        .player-splash__title {
          font-size: 28px;
          font-weight: 700;
          margin-bottom: 12px;
          letter-spacing: 0.02em;
        }
        .player-splash__sub {
          font-size: 14px;
          color: rgba(255, 255, 255, 0.75);
          line-height: 1.6;
        }
        @keyframes fadeIn {
          from {
            opacity: 0;
          }
          to {
            opacity: 1;
          }
        }
      `}</style>
    </div>
  );
}

/* ----------------------------------------------------- status overlay */

interface PlayerStatusOverlayProps {
  deviceId: string;
  status: SsePlayerStatus;
  reloadCounter: number;
  reconnectAttempts: number;
  playlistState: PlaylistState;
  /** AC 60202 Sub-AC 2 — current round-robin index (0-based). */
  currentIndex: number;
  /** Currently-playing ad, surfaced for the demo overlay. */
  currentAd: { adId: string; title: string } | null;
  /**
   * AC 9 — number of ads currently inside their scheduled window AND under
   * their daily cap. Always `<= playlist.ads.length`. Surfaced as
   * `active a/T` next to the total count so the operator can see the
   * window+cap filters firing.
   */
  activeCount: number;
  /**
   * AC 3 Sub-AC 2 — number of in-window ads currently dropped because they
   * have hit their daily cap. Distinct from "out of window" so the operator
   * can tell "all my ads have run out for today" apart from "all my ads
   * are scheduled for later". Hidden when zero to keep the overlay terse.
   */
  cappedCount: number;
  /**
   * AC 3 Sub-AC 2 — the currently-playing ad's plays-today and remaining
   * cap, as a "n/N" or "n/∞" string. `null` means there's no current ad.
   * Surfaced under `now` so the operator can watch the counter tick on
   * every `ended` event.
   */
  capUsage: string | null;
  /**
   * AC 3 Sub-AC 2 — counter day, in `YYYY-MM-DD` form. Surfaced so the
   * operator can confirm the date-rollover happened at local midnight
   * (the row visibly flips to the new date).
   */
  countersDate: string;
  /**
   * AC 60203 Sub-AC 3 — composite selection key for the `<video>` element.
   * Surfaced here purely so the demo operator can see the key flip live
   * whenever the wiring fires (round-robin advance, playlist refetch,
   * mapping change, in-place ad swap).
   */
  selectedAdKey: string;
  onReconnect: () => void;
}

function PlayerStatusOverlay(props: PlayerStatusOverlayProps) {
  const {
    deviceId,
    status,
    reloadCounter,
    reconnectAttempts,
    playlistState,
    currentIndex,
    currentAd,
    activeCount,
    cappedCount,
    capUsage,
    countersDate,
    selectedAdKey,
    onReconnect,
  } = props;

  const playlist =
    playlistState.kind === "ready" ? playlistState.playlist : null;

  return (
    <div className="player-status">
      <div className="player-status__row">
        <strong>SSE</strong>{" "}
        <span className={`player-status__pill player-status__pill--${status}`}>
          {status}
        </span>
        {reconnectAttempts > 0 && (
          <span className="player-status__hint">
            (retry #{reconnectAttempts})
          </span>
        )}
      </div>
      <div className="player-status__row">
        <strong>device</strong> <code>{deviceId.slice(0, 8)}…</code>
      </div>
      {playlist && (
        <>
          <div className="player-status__row">
            <strong>restaurant</strong>{" "}
            <code>
              {playlist.restaurantId
                ? playlist.restaurantId.slice(0, 8) + "…"
                : "(unmapped)"}
            </code>
          </div>
          <div className="player-status__row">
            <strong>ads</strong> {playlist.ads.length}
            {/*
              AC 9 — show how many of the scheduled ads are currently
              inside their wall-clock window. `(active n)` reads as
              "n of T are eligible to play right now". Hidden when all
              ads are active to keep the overlay terse.
            */}
            {activeCount !== playlist.ads.length && (
              <span className="player-status__hint">
                active {activeCount}
              </span>
            )}
            {/*
              AC 3 Sub-AC 2 — show how many of the in-window ads are
              currently dropped for hitting their daily cap. Distinct
              from the `active` line so the operator can tell "5 ads
              scheduled, 2 in window, 1 capped, 1 playing" from "5 ads
              scheduled, 2 in window, both playing".
            */}
            {cappedCount > 0 && (
              <span className="player-status__hint">
                capped {cappedCount}
              </span>
            )}
          </div>
          {/*
            Round-robin pointer (AC 60202 Sub-AC 2). Shown as 1-based for
            the operator (`2/4`) so the live demo reads naturally — and
            the title preview is the easiest visual confirmation that
            advancement is actually firing on `onEnded`.
          */}
          {playlist.ads.length > 0 && (
            <div className="player-status__row">
              <strong>now</strong> {currentIndex + 1}/{playlist.ads.length}
              {currentAd?.title ? (
                <span className="player-status__hint" title={currentAd.title}>
                  {currentAd.title.length > 18
                    ? currentAd.title.slice(0, 18) + "…"
                    : currentAd.title}
                </span>
              ) : null}
              {/*
                AC 3 Sub-AC 2 — current ad's daily-cap usage, shown
                inline so the demo operator can watch it tick after
                each `ended` event. `null` (no current ad) skips the
                cap display entirely.
              */}
              {capUsage && (
                <span className="player-status__hint" title="plays today / cap">
                  {capUsage}
                </span>
              )}
            </div>
          )}
        </>
      )}
      <div className="player-status__row">
        <strong>reloads</strong> {reloadCounter}
      </div>
      {/*
        AC 3 Sub-AC 2 — counter day. Visible so the operator can confirm
        the date-rollover happened at local midnight (the value flips to
        the new YYYY-MM-DD without any operator action).
      */}
      <div className="player-status__row" title="daily-counter day (local)">
        <strong>day</strong> <code>{countersDate}</code>
      </div>
      {/*
        AC 60203 Sub-AC 3 — show the live selection key so the operator
        can visually confirm the video re-render wiring fired. The
        trailing fragment changes on every round-robin advance and on
        every playlist refetch, even when the ad title looks the same.
      */}
      <div className="player-status__row" title={selectedAdKey}>
        <strong>vkey</strong>{" "}
        <code>
          {selectedAdKey.length > 16
            ? "…" + selectedAdKey.slice(-16)
            : selectedAdKey}
        </code>
      </div>
      {(status === "reconnecting" || status === "error") && (
        <button
          type="button"
          className="player-status__btn"
          onClick={onReconnect}
        >
          Reconnect now
        </button>
      )}

      <style jsx>{`
        .player-status {
          position: absolute;
          top: 12px;
          right: 12px;
          background: rgba(0, 0, 0, 0.55);
          color: #fff;
          font-size: 11px;
          line-height: 1.5;
          padding: 10px 12px;
          border-radius: 8px;
          z-index: 20;
          font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
          backdrop-filter: blur(4px);
          min-width: 180px;
        }
        .player-status__row {
          display: flex;
          justify-content: space-between;
          gap: 8px;
        }
        .player-status__row strong {
          color: rgba(255, 255, 255, 0.6);
          font-weight: 600;
          text-transform: uppercase;
          letter-spacing: 0.04em;
          font-size: 10px;
        }
        .player-status__pill {
          display: inline-block;
          padding: 1px 6px;
          border-radius: 999px;
          font-size: 10px;
          font-weight: 700;
          text-transform: uppercase;
          letter-spacing: 0.04em;
        }
        .player-status__pill--idle {
          background: rgba(255, 255, 255, 0.15);
        }
        .player-status__pill--connecting,
        .player-status__pill--reconnecting {
          background: #b48800;
          color: #fff8e1;
        }
        .player-status__pill--open {
          background: #1f7a3d;
          color: #d8f5e2;
        }
        .player-status__pill--error {
          background: #8a1f1f;
          color: #ffe0e0;
        }
        .player-status__hint {
          color: rgba(255, 255, 255, 0.6);
          margin-left: 4px;
        }
        .player-status__btn {
          margin-top: 8px;
          width: 100%;
          padding: 4px 8px;
          font-size: 11px;
          font-family: inherit;
          border: 1px solid rgba(255, 255, 255, 0.4);
          background: transparent;
          color: #fff;
          border-radius: 6px;
          cursor: pointer;
        }
        .player-status__btn:hover {
          background: rgba(255, 255, 255, 0.1);
        }
      `}</style>
    </div>
  );
}

/* --------------------------------------------------- empty / placeholder */

interface PlayerEmptyProps {
  message: string;
  sub?: string;
  tone?: "info" | "error";
  /**
   * AC 4 — full-bleed splash image painted behind the message text.
   *
   * When supplied, the empty state renders this URL as a fullscreen
   * `<img>` (object-fit: cover) sitting BEHIND the title/sub text. The
   * caller passes `/splash.png` for the "outside scheduled window" and
   * "no ads scheduled" branches; transient states (loading / connecting
   * / error) deliberately omit it so the operator still sees a plain
   * status message in those debugging-relevant cases.
   *
   * Why a real `<img>` rather than a CSS `background-image`:
   *   - Lazy-decode pipelines on Android WebView are kinder to `<img>`
   *     elements than to background images on a positioned div, so the
   *     splash paints faster on a slow restaurant network.
   *   - `aria-hidden="true"` keeps the visual splash out of the
   *     accessibility tree while the message text remains the
   *     authoritative status announcement.
   *   - The `<img>` element honours HTTP cache semantics natively, so
   *     the splash is served from disk after the first paint even when
   *     the WebView purges the styled-jsx stylesheet across reloads.
   */
  splashImage?: string;
}

function PlayerEmpty({
  message,
  sub,
  tone = "info",
  splashImage,
}: PlayerEmptyProps) {
  return (
    <div
      className="player-empty"
      style={{
        color: tone === "error" ? "#ff8a8a" : "rgba(255,255,255,0.85)",
      }}
    >
      {splashImage && (
        // The splash image is decorative — the sibling title/sub
        // <div>s carry the actual semantic status for screen readers
        // and accessibility tooling.
        <img
          className="player-empty__splash"
          src={splashImage}
          alt=""
          aria-hidden="true"
          draggable={false}
        />
      )}
      <div className="player-empty__title">{message}</div>
      {sub && <div className="player-empty__sub">{sub}</div>}
      <style jsx>{`
        .player-empty {
          position: absolute;
          inset: 0;
          display: flex;
          flex-direction: column;
          align-items: center;
          justify-content: center;
          text-align: center;
          padding: 32px;
          /* Solid backdrop in case the splash image is missing/slow —
             keeps the screen from flashing white on the WebView. */
          background: #000;
        }
        .player-empty__splash {
          position: absolute;
          inset: 0;
          width: 100%;
          height: 100%;
          object-fit: cover;
          /* Sit behind the message text. The parent's flex centring still
             places the title/sub on top thanks to z-index stacking. */
          z-index: 0;
          pointer-events: none;
          user-select: none;
        }
        .player-empty__title,
        .player-empty__sub {
          position: relative;
          z-index: 1;
          /* Subtle shadow so the title stays legible regardless of which
             part of the splash image happens to sit behind the text. */
          text-shadow: 0 2px 8px rgba(0, 0, 0, 0.65);
        }
        .player-empty__title {
          font-size: 22px;
          font-weight: 600;
          margin-bottom: 8px;
        }
        .player-empty__sub {
          font-size: 13px;
          opacity: 0.85;
        }
      `}</style>
    </div>
  );
}

/* ------------------------------------------------------------ helpers */

function describeError(err: unknown): string {
  if (err instanceof ApiError) {
    return `HTTP ${err.status} (${err.url})`;
  }
  if (err instanceof Error) return err.message;
  return "unknown error";
}

/**
 * AC 3 Sub-AC 2 — render the current ad's daily-cap usage as a compact
 * "plays/cap" string for the status overlay. Shapes:
 *
 *   - `null`           — no current ad (the overlay omits the column entirely).
 *   - `"3/∞"`          — ad with no cap configured (`dailyCount` is null).
 *   - `"5/200"`        — ad with a 200 cap, 5 plays so far today.
 *   - `"7/7"`          — ad just hit its cap on the most recent `ended`
 *                        event; `filterUnderCap` will drop it from the
 *                        active set on the next render.
 *
 * `getRemaining` is the canonical "is there headroom" check; we fold its
 * result with the raw count to show both halves of the fraction. Pure /
 * no React deps so it can sit alongside the other formatter helpers.
 */
function formatCapUsage(
  ad: PlaylistAd | null,
  counts: Record<string, number>,
): string | null {
  if (!ad) return null;
  const used = counts[ad.adId] ?? 0;
  const remaining = getRemaining(ad, counts);
  if (remaining === null) return `${used}/∞`; // ∞ for unlimited
  // `cap = used + max(remaining, 0)` reconstructs the configured cap
  // without re-importing `normaliseCap` here. For a capped-out ad
  // `remaining` is 0 and `cap === used`, which renders as "N/N".
  const cap = used + Math.max(0, remaining);
  return `${used}/${cap}`;
}

/**
 * AC 9 — produce a human-readable hint for the "Outside scheduled
 * window" splash by finding the next start_time the device will see.
 *
 * Strategy:
 *   - Scan the playlist for the earliest `startTime` strictly after the
 *     current local minute-of-day. That's the window the device will
 *     enter next today.
 *   - If no window opens later today, return the *first* (smallest)
 *     start_time across the playlist — that's the window the device
 *     will enter when the next day starts.
 *   - Falls through to a generic message if no parseable times exist.
 *
 * Stays here (rather than in `lib/playlist.ts`) because this is purely
 * UI text — the schedule predicate is the canonical helper.
 */
function describeNextWindow(ads: PlaylistAd[]): string {
  const now = new Date();
  const nowMin = now.getHours() * 60 + now.getMinutes();
  const starts: Array<{ raw: string; min: number }> = [];
  for (const ad of ads) {
    if (!ad.startTime) continue;
    const m = /^([0-9]{2}):([0-9]{2})$/.exec(ad.startTime);
    if (!m) continue;
    const min = Number(m[1]) * 60 + Number(m[2]);
    if (Number.isFinite(min)) starts.push({ raw: ad.startTime, min });
  }
  if (starts.length === 0) {
    return "잠시 후 광고가 다시 송출됩니다";
  }
  // First, look for the next window strictly after `now` today.
  const upcomingToday = starts
    .filter((s) => s.min > nowMin)
    .sort((a, b) => a.min - b.min)[0];
  if (upcomingToday) {
    return `다음 광고는 ${upcomingToday.raw} 부터`;
  }
  // Otherwise wrap to the first window of tomorrow.
  const firstTomorrow = [...starts].sort((a, b) => a.min - b.min)[0];
  return `내일 ${firstTomorrow.raw} 부터 다시 송출됩니다`;
}

export default PlayerClient;
