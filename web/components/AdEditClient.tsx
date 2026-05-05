"use client";

/**
 * /ads/[id] 페이지의 클라이언트 호스트.
 *
 * GET /api/ads/{id} 로 단일 광고를 받아 AdScheduleForm 의 initialValues 로
 * 주입. 광고 메타(제목/영상/생성)+상태 배지+스케줄 요약을 좌측 액센트
 * 보더 카드로 표시. 송출 디바이스는 미니 테이블(데스크탑) + 카드 리스트
 * (모바일) 듀얼 렌더.
 */

import { useCallback, useEffect, useState } from "react";
import Link from "next/link";

import { ApiError } from "@/lib/api";
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
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";

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
  }, [refetch]);

  useDataChanged(["device-queue", "ad"], refetch);

  return (
    <section>
      <h2 className="mb-1 text-xs font-semibold uppercase tracking-wider text-muted-foreground">
        송출 현황
      </h2>
      <p className="mb-3 text-xs text-muted-foreground">
        이 광고가 깔린 디바이스 — 운영자(OPERATOR) 가 큐에 담았을 때 여기
        표시됩니다. 디바이스 매칭은 플랫폼 운영자가 통제합니다.
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
        <>
          <div className="hidden md:block w-full overflow-x-auto rounded-lg border border-border bg-card">
            <Table aria-label="송출 디바이스">
              <TableHeader>
                <TableRow>
                  <TableHead className="w-[92px]">상태</TableHead>
                  <TableHead className="min-w-[180px]">디바이스</TableHead>
                  <TableHead className="min-w-[160px]">음식점</TableHead>
                  <TableHead className="w-[180px]">큐 등록일</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {deployments.map((d) => (
                  <TableRow key={d.deviceId}>
                    <TableCell>
                      {d.currentlyPlaying ? (
                        <Badge variant="ok">🔴 LIVE</Badge>
                      ) : (
                        <Badge variant="muted">대기</Badge>
                      )}
                    </TableCell>
                    <TableCell>
                      <strong>{d.deviceName}</strong>
                    </TableCell>
                    <TableCell className="text-muted-foreground">
                      {d.restaurantName ?? "(미할당)"}
                    </TableCell>
                    <TableCell className="whitespace-nowrap text-xs text-muted-foreground">
                      {new Date(d.addedAt).toLocaleString("ko-KR")}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
          <ul
            className="md:hidden flex flex-col gap-2.5"
            aria-label="송출 디바이스 (모바일 보기)"
          >
            {deployments.map((d) => (
              <li
                key={d.deviceId}
                className="rounded-lg border border-border bg-card p-3"
              >
                <div className="flex items-baseline justify-between gap-2">
                  <strong>{d.deviceName}</strong>
                  {d.currentlyPlaying ? (
                    <Badge variant="ok">🔴 LIVE</Badge>
                  ) : (
                    <Badge variant="muted">대기</Badge>
                  )}
                </div>
                <div className="mt-1 text-sm text-muted-foreground">
                  {d.restaurantName ?? "(미할당)"}
                </div>
                <div className="mt-0.5 text-xs text-muted-foreground">
                  큐 등록 {new Date(d.addedAt).toLocaleString("ko-KR")}
                </div>
              </li>
            ))}
          </ul>
        </>
      )}
    </section>
  );
}

export default AdEditClient;
