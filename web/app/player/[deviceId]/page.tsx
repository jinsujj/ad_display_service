/**
 * Player page route shell — `/player/{deviceId}`.
 *
 * AC 5, Sub-AC 4 (Player/Android):
 *   "Implement SSE subscription on player page so device reloads schedule
 *    immediately when remapped without manual refresh."
 *
 * The Android WebView wrapper opens this route on boot (see
 * `android/app/src/main/java/com/owldev/adsignage/MainActivity.kt`,
 * `buildPlayerUrl(deviceId)`), so the entire playback runtime lives in
 * the browser on the Android device — including the SSE subscription
 * that this sub-AC delivers.
 *
 * This page is intentionally thin:
 *   - Server Component shell (this file): pulls the deviceId out of the
 *     route params, renders the client component below, and sets a
 *     player-friendly viewport / page metadata.
 *   - Client Component (`PlayerClient`): owns the SSE subscription via
 *     `usePlayerSse` and the playlist refetch on every remap event.
 *
 * Sibling sub-ACs (round-robin playback within schedule windows, splash
 * screens outside schedule, video range fetching) compose on top of the
 * `playlist` state surfaced by `PlayerClient` — they don't need to know
 * SSE exists.
 */

import type { Metadata } from "next";
import { PlayerClient } from "./PlayerClient";

// The player must always reflect the live device → restaurant mapping;
// caching here would hide a remap until the next nav. Disable both the
// data cache and full-page static rendering.
export const dynamic = "force-dynamic";
export const revalidate = 0;

export const metadata: Metadata = {
  title: "AdSignage Player",
  description:
    "Restaurant fridge digital signage player — subscribes to SSE for live remap and playlist updates.",
  // Player runs fullscreen in the WebView (see MainActivity immersive
  // mode) — discourage user-zoom which makes the touchscreen demo brittle.
  viewport: {
    width: "device-width",
    initialScale: 1,
    maximumScale: 1,
    userScalable: false,
  },
};

interface PlayerRouteProps {
  params: { deviceId: string };
}

export default function PlayerPage({ params }: PlayerRouteProps) {
  const { deviceId } = params;
  return <PlayerClient deviceId={deviceId} />;
}
