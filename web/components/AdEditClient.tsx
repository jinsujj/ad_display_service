"use client";

/**
 * /ads/[id] 페이지의 클라이언트 호스트.
 *
 * GET /api/ads/{id} 로 단일 광고를 받아 AdScheduleForm 의 initialValues 로
 * 주입. 광고 메타(제목/영상/생성)+상태 배지+스케줄 요약을 좌측 액센트
 * 보더 카드로 표시. 송출 디바이스는 미니 테이블(데스크탑) + 카드 리스트
 * (모바일) 듀얼 렌더.
 */

import { useCallback, useEffect, useMemo, useState } from "react";
import Link from "next/link";

import { ApiError, apiUrl } from "@/lib/api";
import {
  AD_STATUS_LABEL,
  getAd,
  getAdDeployments,
  type AdDeploymentItem,
  type AdResponse,
} from "@/lib/ads";
import { useDataChanged } from "@/lib/dataEvents";
import { AdScheduleForm } from "./AdScheduleForm";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Card, CardContent } from "@/components/ui/card";
import { cn } from "@/lib/utils";

type State =
  | { kind: "loading" }
  | { kind: "ready"; ad: AdResponse }
  | { kind: "not-found" }
  | { kind: "error"; message: string };

interface Props {
  adId: string;
}

export function AdEditClient({ adId }: Props) {
  const [state, setState] = useState<State>({ kind: "loading" });

  useEffect(() => {
    let cancelled = false;
    setState({ kind: "loading" });
    getAd(adId)
      .then((ad) => {
        if (!cancelled) setState({ kind: "ready", ad });
      })
      .catch((err) => {
        if (cancelled) return;
        if (err instanceof ApiError && err.status === 404) {
          setState({ kind: "not-found" });
          return;
        }
        const msg =
          err instanceof ApiError
            ? `HTTP ${err.status}`
            : err instanceof Error
              ? err.message
              : "알 수 없는 오류";
        setState({ kind: "error", message: msg });
      });
    return () => {
      cancelled = true;
    };
  }, [adId]);

  if (state.kind === "loading") {
    return (
      <div className="text-sm text-muted-foreground">
        광고 정보를 불러오는 중…
      </div>
    );
  }

  if (state.kind === "not-found") {
    return (
      <Alert variant="destructive">
        <AlertDescription>
          해당 광고를 찾을 수 없습니다 (또는 본인 광고가 아닙니다).{" "}
          <Link
            href="/ads"
            className="underline-offset-4 hover:underline"
          >
            광고 목록으로 돌아가기
          </Link>
        </AlertDescription>
      </Alert>
    );
  }

  if (state.kind === "error") {
    return (
      <Alert variant="destructive">
        <AlertDescription>
          광고 정보를 불러오지 못했습니다: {state.message}
        </AlertDescription>
      </Alert>
    );
  }

  const ad = state.ad;
  const statusVariant =
    ad.status === "ACTIVE"
      ? ("ok" as const)
      : ad.status === "EXPIRED"
        ? ("warn" as const)
        : ("muted" as const);

  return (
    <div className="space-y-6">
      <Card className="border-l-4 border-l-accent">
        <CardContent className="pt-4">
          <div className="flex flex-wrap items-baseline gap-2">
            <strong className="text-base">{ad.title}</strong>
            <Badge variant={statusVariant}>
              {AD_STATUS_LABEL[ad.status]}
            </Badge>
          </div>
          <dl className="mt-2 space-y-1 text-xs text-muted-foreground">
            <div>
              현재 스케줄: {ad.startTime} ~ {ad.endTime} · 일일{" "}
              {ad.dailyPlayCount}회
            </div>
            <div>
              캠페인 기간: {ad.campaignStartDate} ~ {ad.campaignEndDate}
            </div>
          </dl>
        </CardContent>
      </Card>

      <section>
        <h2 className="mb-3 text-xs font-semibold uppercase tracking-wider text-muted-foreground">
          스케줄
        </h2>
        <AdScheduleForm
          adId={ad.id}
          initialValues={{
            startTime: ad.startTime,
            endTime: ad.endTime,
            dailyPlayCount: ad.dailyPlayCount,
            campaignStartDate: ad.campaignStartDate,
            campaignEndDate: ad.campaignEndDate,
          }}
        />
      </section>

      <AdDeploymentsSection adId={ad.id} />
    </div>
  );
}

/* --------------------------- 송출 현황 ----------------- */

/** 광고가 송출 중인지 폴링 주기. DeviceMonitorWall 1.5s 와 비슷한 체감
 *  속도를 위해 2초 — 광고 1편 당 STARTED 가 ~30s 마다 발사되므로 충분히 빠름. */
const AD_DEPLOYMENT_POLL_MS = 2_000;

function AdDeploymentsSection({ adId }: { adId: string }) {
  const [deployments, setDeployments] = useState<AdDeploymentItem[] | null>(
    null,
  );
  const [error, setError] = useState<string | null>(null);

  const refetch = useCallback(() => {
    getAdDeployments(adId)
      .then((rows) => {
        setDeployments(rows);
        setError(null);
      })
      .catch((err) => {
        const msg =
          err instanceof ApiError
            ? `HTTP ${err.status}`
            : err instanceof Error
              ? err.message
              : "알 수 없는 오류";
        setError(msg);
      });
  }, [adId]);

  useEffect(() => {
    refetch();
    // 라이브 LIVE/대기 표시가 디바이스 페이지와 어긋나지 않도록 주기 폴링.
    // 처음엔 mutation 이벤트(useDataChanged) 만으로 충분할 줄 알았는데,
    // STARTED 이벤트는 데이터 events 채널을 안 타서 별도 폴링이 필요.
    const t = setInterval(refetch, AD_DEPLOYMENT_POLL_MS);
    return () => clearInterval(t);
  }, [refetch]);

  useDataChanged(["device-queue", "ad"], refetch);

  return (
    <section>
      <h2 className="mb-1 text-xs font-semibold uppercase tracking-wider text-muted-foreground">
        송출 현황
      </h2>
      <p className="mb-3 text-xs text-muted-foreground">
        이 광고가 송출 중인 디바이스 — 운영자가 큐에 담은 디바이스만 표시됩니다.
        LIVE 카드의 영상은 디바이스가 실제로 지금 송출 중인 광고를 미러링합니다.
      </p>

      {error ? (
        <Alert variant="destructive">
          <AlertDescription>
            송출 현황을 불러오지 못했습니다: {error}
          </AlertDescription>
        </Alert>
      ) : deployments === null ? (
        <div className="text-sm text-muted-foreground">
          송출 현황 불러오는 중…
        </div>
      ) : deployments.length === 0 ? (
        <div className="rounded-lg border border-dashed border-border bg-card p-6 text-center text-sm text-muted-foreground">
          이 광고는 아직 어떤 디바이스에도 깔려 있지 않습니다. 운영자에게
          매칭을 요청하세요.
        </div>
      ) : (
        <div className="grid grid-cols-[repeat(auto-fill,minmax(min(220px,100%),1fr))] gap-3">
          {deployments.map((d) => (
            <DeploymentCard key={d.deviceId} deployment={d} />
          ))}
        </div>
      )}
    </section>
  );
}

function DeploymentCard({ deployment }: { deployment: AdDeploymentItem }) {
  const live = deployment.currentlyPlaying;
  return (
    <div
      className={cn(
        "flex flex-col rounded-lg border bg-card p-2.5 transition-colors",
        live ? "border-emerald-500/40" : "border-border",
      )}
    >
      <div className="mb-2 flex items-center justify-between gap-2">
        <div className="min-w-0 flex-1">
          <div className="truncate text-sm font-semibold" title={deployment.deviceName}>
            {deployment.deviceName}
          </div>
          <div
            className="truncate text-[11px] text-muted-foreground"
            title={deployment.restaurantName ?? "미할당"}
          >
            📍 {deployment.restaurantName ?? "미할당"}
          </div>
        </div>
        {live ? (
          <Badge variant="ok" className="shrink-0">
            🔴 LIVE
          </Badge>
        ) : (
          <Badge variant="muted" className="shrink-0">
            대기
          </Badge>
        )}
      </div>

      {live && deployment.startedAt ? (
        <LiveAdPane
          videoFilename={deployment.videoFilename}
          startedAt={deployment.startedAt}
        />
      ) : (
        <IdleAdPane addedAt={deployment.addedAt} />
      )}
    </div>
  );
}

function LiveAdPane({
  videoFilename,
  startedAt,
}: {
  videoFilename: string;
  startedAt: string;
}) {
  // adId+startedAt 조합으로 video remount 트리거 — 디바이스가 다음 광고로
  // 넘어갔다 같은 광고로 다시 돌아왔을 때 깨끗하게 재시작.
  const videoKey = `${videoFilename}|${startedAt}`;
  const src = useMemo(
    () => apiUrl(`/api/videos/${encodeURIComponent(videoFilename)}`),
    [videoFilename],
  );

  // 디바이스 progress 와 시각 동기 — DeviceMonitorWall 의 LivePane 과 같은
  // 패턴. duration 알려진 후 startedAt 기준으로 elapsed % duration 위치로 seek.
  const onLoadedMetadata = useCallback(
    (e: React.SyntheticEvent<HTMLVideoElement>) => {
      const video = e.currentTarget;
      const duration = video.duration;
      if (!Number.isFinite(duration) || duration <= 0) return;
      try {
        const startedMs = new Date(startedAt).getTime();
        if (Number.isNaN(startedMs)) return;
        const elapsedSec = (Date.now() - startedMs) / 1000;
        if (elapsedSec < 0) return;
        const seekTo = elapsedSec % duration;
        video.currentTime = Math.min(seekTo, Math.max(0, duration - 0.1));
      } catch {
        // seek 실패는 시각적 딜레이로 보일 뿐, 재생 자체는 계속됨.
      }
    },
    [startedAt],
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
        onLoadedMetadata={onLoadedMetadata}
        className="block h-full w-full object-cover"
      />
    </div>
  );
}

function IdleAdPane({ addedAt }: { addedAt: string }) {
  return (
    <div className="flex aspect-video flex-col items-center justify-center gap-1 rounded-md border border-dashed border-border bg-gradient-to-br from-accent/10 to-accent/[0.02] p-2 text-center text-xs text-muted-foreground">
      <div>송출 대기</div>
      <div className="text-[11px] opacity-80">
        큐 등록 {new Date(addedAt).toLocaleDateString("ko-KR")}
      </div>
    </div>
  );
}

export default AdEditClient;
