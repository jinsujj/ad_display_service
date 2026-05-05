"use client";

/**
 * 디바이스 모니터링 월 — 어드민이 한눈에 "지금 어떤 디바이스가 어떤 광고를
 * 송출 중인지" 보는 카드 그리드.
 *
 * **이 컴포넌트는 시뮬레이션을 하지 않는다.** 카드의 영상은 서버가 가장 최근
 * STARTED play-event 로 결정한 *디바이스의 실제 송출 광고* 만 미러링한다.
 * 디바이스 앱이 종료된 카드는 "오프라인" 으로 회색 처리되어 죽은 디바이스를
 * 즉시 식별 가능.
 *
 * 카드 상태 분기:
 *   - LIVE       : online && currentAd → 그 광고를 muted/autoplay/loop 재생
 *   - 송출 대기  : online && !currentAd → placeholder ("큐 비었거나 시간 윈도우 밖")
 *   - 오프라인   : !online → 회색 카드, "🔌 오프라인 · 마지막 활동 N분 전"
 *
 * 폴링 주기는 상위(`MyDevicesList`) 의 AUTO_REFRESH_MS 가 결정한다.
 */

import { useCallback, useMemo } from "react";
import Link from "next/link";

import { apiUrl } from "@/lib/api";
import type { CurrentAd, DeviceListItem } from "@/lib/devices";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";

interface Props {
  devices: DeviceListItem[];
  /** 현재 목록을 다시 fetch — "지금 새로고침" 버튼이 호출. */
  onRefresh?: () => void;
  refreshing?: boolean;
}

export function DeviceMonitorWall({ devices, onRefresh, refreshing }: Props) {
  if (devices.length === 0) return null;

  const liveCount = devices.filter((d) => d.online && d.currentAd).length;
  const onlineCount = devices.filter((d) => d.online).length;
  const offlineCount = devices.length - onlineCount;

  return (
    <section aria-label="디바이스 송출 모니터링" className="mb-6">
      <div className="mb-2 flex flex-wrap items-baseline justify-between gap-3">
        <div>
          <h2 className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">
            송출 모니터링
          </h2>
          <p className="mt-1 text-xs text-muted-foreground">
            서버에 도달한 가장 최근 STARTED 이벤트 기준 — 디바이스가 실제로
            지금 송출 중인 광고만 표시합니다. 앱이 종료된 디바이스는 자동으로
            오프라인 처리됩니다.
          </p>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <Badge variant="ok">🔴 LIVE {liveCount}</Badge>
          {offlineCount > 0 && (
            <Badge variant="muted">🔌 오프라인 {offlineCount}</Badge>
          )}
          {onRefresh && (
            <Button
              type="button"
              variant="outline"
              size="sm"
              onClick={onRefresh}
              disabled={refreshing}
            >
              {refreshing ? "새로고침 중…" : "↻ 지금 새로고침"}
            </Button>
          )}
        </div>
      </div>

      {/* minmax(min(180px, 100%), 1fr) — 화면이 180px 보다 좁으면 1컬럼,
          그렇지 않으면 180px 단위로 자동 컬럼. 폰(360~414px)에서 2컬럼이
          자연스럽게 나오도록. */}
      <div className="grid grid-cols-[repeat(auto-fill,minmax(min(180px,100%),1fr))] gap-3">
        {devices.map((d) => (
          <DeviceMonitorCard key={d.deviceId} device={d} />
        ))}
      </div>
    </section>
  );
}

function DeviceMonitorCard({ device }: { device: DeviceListItem }) {
  const restaurantLabel = device.currentRestaurant?.restaurantName;
  const offline = !device.online;

  return (
    <Link
      href={`/devices/${encodeURIComponent(device.deviceId)}`}
      className={cn(
        "block rounded-lg border bg-card p-2.5 text-card-foreground no-underline transition-[border-color,opacity]",
        offline && "border-white/5 opacity-60",
        !offline &&
          device.currentAd &&
          "border-emerald-500/40 hover:border-emerald-500/60",
        !offline && !device.currentAd && "border-border hover:border-accent/40"
      )}
    >
      <div className="mb-2 flex items-center justify-between gap-2">
        <div className="min-w-0 flex-1">
          <div
            className="flex items-center gap-1.5 truncate text-[13px] font-semibold"
            title={device.deviceName}
          >
            <span
              aria-hidden="true"
              className={cn(
                "h-2 w-2 shrink-0 rounded-full",
                offline && "bg-gray-500",
                !offline && device.currentAd && "bg-emerald-500 shadow-[0_0_6px_rgba(34,197,94,0.7)]",
                !offline && !device.currentAd && "bg-amber-500"
              )}
            />
            <span className="truncate">
              {device.deviceName || "(이름 없음)"}
            </span>
          </div>
          <div
            className="mt-0.5 truncate text-[11px] text-muted-foreground"
            title={restaurantLabel ?? "미할당"}
          >
            {restaurantLabel ? `📍 ${restaurantLabel}` : "📍 미할당"}
          </div>
        </div>
        <CardBadge device={device} />
      </div>

      {offline ? (
        <OfflinePane lastSeenAt={device.lastSeenAt} />
      ) : device.currentAd ? (
        <LivePane currentAd={device.currentAd} />
      ) : (
        <IdlePane queuedCount={device.queuedAds.length} />
      )}
    </Link>
  );
}

function CardBadge({ device }: { device: DeviceListItem }) {
  if (!device.online) {
    return (
      <Badge variant="muted" className="shrink-0">
        오프라인
      </Badge>
    );
  }
  if (device.currentAd) {
    return (
      <Badge variant="ok" className="shrink-0">
        🔴 LIVE
      </Badge>
    );
  }
  return (
    <Badge variant="muted" className="shrink-0">
      대기
    </Badge>
  );
}

function LivePane({ currentAd }: { currentAd: CurrentAd }) {
  // adId+startedAt 을 key 로 잡으면 디바이스가 다음 광고로 넘어갔을 때
  // <video> 가 강제 remount 되어 새 src 로 깨끗하게 재시작.
  const videoKey = `${currentAd.adId}|${currentAd.startedAt}`;
  const src = useMemo(
    () => apiUrl(`/api/videos/${encodeURIComponent(currentAd.videoFilename)}`),
    [currentAd.videoFilename],
  );

  // 디바이스가 광고 N초째 재생 중일 때, 모니터 영상을 *같은 N초 위치* 로
  // 점프시켜 시각적 동기를 맞춘다. 이 시킹이 없으면 모니터는 항상 0초부터
  // 시작해 운영자 눈에 "디바이스랑 어긋나 보이는" 딜레이로 보인다.
  const handleLoadedMetadata = useCallback(
    (e: React.SyntheticEvent<HTMLVideoElement>) => {
      const video = e.currentTarget;
      const duration = video.duration;
      if (!Number.isFinite(duration) || duration <= 0) return;
      try {
        const startedMs = new Date(currentAd.startedAt).getTime();
        if (Number.isNaN(startedMs)) return;
        const elapsedSec = (Date.now() - startedMs) / 1000;
        if (elapsedSec < 0) return;
        const seekTo = elapsedSec % duration;
        video.currentTime = Math.min(seekTo, Math.max(0, duration - 0.1));
      } catch {
        // seek 실패는 시각적 딜레이로 보일 뿐, 재생 자체는 계속됨 — swallow.
      }
    },
    [currentAd.startedAt],
  );

  return (
    <div className="relative aspect-video overflow-hidden rounded-md bg-black">
      <video
        key={videoKey}
        src={src}
        muted
        autoPlay
        playsInline
        loop
        preload="auto"
        onLoadedMetadata={handleLoadedMetadata}
        className="block h-full w-full object-cover"
      />
      <div className="absolute inset-x-0 bottom-0 flex min-w-0 items-center gap-1.5 bg-gradient-to-t from-black/70 to-transparent px-2 py-1.5 text-[11px] text-white">
        <span aria-hidden="true" className="shrink-0 text-red-500">
          ●
        </span>
        <span
          className="min-w-0 flex-1 truncate"
          title={currentAd.title}
        >
          {currentAd.title || currentAd.videoFilename}
        </span>
      </div>
    </div>
  );
}

function IdlePane({ queuedCount }: { queuedCount: number }) {
  return (
    <div className="flex aspect-video flex-col items-center justify-center gap-1 rounded-md border border-dashed border-border bg-gradient-to-br from-accent/10 to-accent/[0.02] p-2 text-center text-xs text-muted-foreground">
      <div>송출 대기</div>
      <div className="text-[11px] opacity-80">
        {queuedCount > 0
          ? "큐는 있지만 시간 윈도우 밖이거나 일일 한도 도달"
          : "큐가 비었습니다 — 디바이스 상세에서 광고를 담아주세요"}
      </div>
    </div>
  );
}

function OfflinePane({ lastSeenAt }: { lastSeenAt: string | null }) {
  const ago = lastSeenAt ? formatTimeAgo(lastSeenAt) : null;
  return (
    <div className="flex aspect-video flex-col items-center justify-center gap-1 rounded-md border border-dashed border-white/10 bg-white/[0.02] p-2 text-center text-xs text-muted-foreground">
      <div className="text-lg">🔌</div>
      <div>오프라인</div>
      {ago && <div className="text-[11px] opacity-80">마지막 활동 {ago}</div>}
    </div>
  );
}

function formatTimeAgo(iso: string): string {
  try {
    const then = new Date(iso).getTime();
    if (Number.isNaN(then)) return "알 수 없음";
    const sec = Math.max(0, Math.floor((Date.now() - then) / 1000));
    if (sec < 60) return `${sec}초 전`;
    const min = Math.floor(sec / 60);
    if (min < 60) return `${min}분 전`;
    const hr = Math.floor(min / 60);
    if (hr < 24) return `${hr}시간 전`;
    const day = Math.floor(hr / 24);
    return `${day}일 전`;
  } catch {
    return "알 수 없음";
  }
}

export default DeviceMonitorWall;
