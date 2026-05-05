"use client";

/**
 * 내 광고 목록.
 *
 * 데스크탑(>=md)은 7컬럼 테이블, 모바일(<md)은 카드 리스트로 자동 전환.
 * 모바일 카드는 광고 ID 컬럼을 드롭하고 상태 배지 + 제목/영상 + 시간/횟수
 * + 캠페인 기간 + 액션을 세로로 배치.
 *
 * 제거는 즉시 로컬 미러에서 빠지고 백엔드가 PLAYLIST_UPDATE 를 발행하므로
 * 송출 중이던 디바이스도 SSE 로 새 플레이리스트를 받는다.
 */

import { useCallback, useEffect, useState } from "react";
import Link from "next/link";

import { ApiError } from "@/lib/api";
import {
  AD_STATUS_LABEL,
  deleteAd,
  listMyAds,
  type AdResponse,
  type AdStatus,
} from "@/lib/ads";
import { useDataChanged } from "@/lib/dataEvents";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
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
  | { kind: "ready"; ads: AdResponse[] }
  | { kind: "error"; message: string };

export function MyAdsList() {
  const [state, setState] = useState<State>({ kind: "loading" });
  const [removingId, setRemovingId] = useState<string | null>(null);

  const refetch = useCallback(() => {
    listMyAds()
      .then((ads) => setState({ kind: "ready", ads }))
      .catch((err) => {
        const msg =
          err instanceof ApiError
            ? `HTTP ${err.status}`
            : err instanceof Error
              ? err.message
              : "알 수 없는 오류";
        setState((prev) =>
          prev.kind === "ready" ? prev : { kind: "error", message: msg },
        );
      });
  }, []);

  useEffect(() => {
    refetch();
  }, [refetch]);

  // 다른 화면에서 광고 mutation 일어나면 자동 새로고침. 영상 업로드도 다음
  // 광고 만들기 흐름과 연결되는 경우가 많아 video 도 같이 listen.
  useDataChanged(["ad", "video"], refetch);

  const handleDelete = useCallback(async (ad: AdResponse) => {
    const ok = window.confirm(
      `광고 "${ad.title}" 를 삭제할까요?\n\n` +
        `· 삭제 후엔 되돌릴 수 없습니다.\n` +
        `· 송출 중이던 디바이스는 즉시 새 플레이리스트로 전환됩니다.`,
    );
    if (!ok) return;
    setRemovingId(ad.id);
    try {
      await deleteAd(ad.id);
      setState((prev) =>
        prev.kind === "ready"
          ? { kind: "ready", ads: prev.ads.filter((x) => x.id !== ad.id) }
          : prev,
      );
    } catch (err) {
      const msg =
        err instanceof ApiError
          ? `HTTP ${err.status}`
          : err instanceof Error
            ? err.message
            : "알 수 없는 오류";
      window.alert(`광고 삭제에 실패했습니다: ${msg}`);
    } finally {
      setRemovingId(null);
    }
  }, []);

  if (state.kind === "loading") {
    return (
      <div className="text-sm text-muted-foreground">
        광고 목록을 불러오는 중…
      </div>
    );
  }
  if (state.kind === "error") {
    return (
      <Alert variant="destructive">
        <AlertDescription>
          광고 목록을 불러오지 못했습니다: {state.message}
        </AlertDescription>
      </Alert>
    );
  }
  if (state.ads.length === 0) {
    return (
      <div className="rounded-lg border border-dashed border-border bg-card p-8 text-center text-sm text-muted-foreground">
        아직 만든 광고가 없습니다. 위의 &quot;새 광고 만들기&quot; 버튼으로
        시작하세요.
      </div>
    );
  }

  return (
    <>
      {/* 데스크탑 — 7컬럼 테이블 */}
      <div className="hidden md:block w-full overflow-x-auto rounded-lg border border-border bg-card">
        <Table aria-label="내 광고 목록">
          <TableHeader>
            <TableRow>
              <TableHead className="w-[92px]">상태</TableHead>
              <TableHead className="min-w-[240px]">제목</TableHead>
              <TableHead className="w-[150px]">일일 시간</TableHead>
              <TableHead className="w-[92px]">일일 횟수</TableHead>
              <TableHead className="w-[160px]">캠페인 기간</TableHead>
              <TableHead className="w-[180px] text-right">액션</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {state.ads.map((ad) => {
              const removing = removingId === ad.id;
              return (
                <TableRow key={ad.id}>
                  <TableCell>
                    <Badge variant={statusBadgeVariant(ad.status)}>
                      {AD_STATUS_LABEL[ad.status]}
                    </Badge>
                  </TableCell>
                  <TableCell>
                    <Link
                      href={`/ads/${ad.id}`}
                      className="font-semibold hover:text-accent hover:underline underline-offset-4"
                    >
                      {ad.title}
                    </Link>
                  </TableCell>
                  <TableCell className="whitespace-nowrap">
                    {ad.startTime} ~ {ad.endTime}
                  </TableCell>
                  <TableCell>{ad.dailyPlayCount}회</TableCell>
                  <TableCell className="whitespace-nowrap text-xs text-muted-foreground">
                    {ad.campaignStartDate}
                    <br />~ {ad.campaignEndDate}
                  </TableCell>
                  <TableCell>
                    <div className="flex items-center justify-end gap-1.5">
                      <Button
                        asChild
                        variant="outline"
                        size="sm"
                        aria-label={`Edit ${ad.title}`}
                      >
                        <Link href={`/ads/${ad.id}`}>편집</Link>
                      </Button>
                      <Button
                        type="button"
                        variant="outline"
                        size="sm"
                        onClick={() => handleDelete(ad)}
                        disabled={removing}
                        aria-label={`Delete ${ad.title}`}
                        title="이 광고 삭제"
                        className="border-destructive/35 text-destructive hover:bg-destructive/10"
                      >
                        {removing ? "삭제 중…" : "✕ 제거"}
                      </Button>
                    </div>
                  </TableCell>
                </TableRow>
              );
            })}
          </TableBody>
        </Table>
      </div>

      {/* 모바일 — 카드 리스트. 광고 ID 컬럼은 드롭. */}
      <ul
        className="md:hidden flex flex-col gap-2.5"
        aria-label="내 광고 (모바일 보기)"
      >
        {state.ads.map((ad) => {
          const removing = removingId === ad.id;
          return (
            <li
              key={ad.id}
              className="rounded-lg border border-border bg-card p-3"
            >
              <div className="flex items-baseline justify-between gap-2">
                <Link
                  href={`/ads/${ad.id}`}
                  className="truncate font-semibold hover:text-accent"
                >
                  {ad.title}
                </Link>
                <Badge variant={statusBadgeVariant(ad.status)}>
                  {AD_STATUS_LABEL[ad.status]}
                </Badge>
              </div>
              <div className="mt-2 text-sm">
                일일 {ad.startTime}~{ad.endTime} · {ad.dailyPlayCount}회
              </div>
              <div className="text-xs text-muted-foreground">
                캠페인 {ad.campaignStartDate} ~ {ad.campaignEndDate}
              </div>
              <div className="mt-3 flex gap-2">
                <Button
                  asChild
                  variant="outline"
                  className="flex-1"
                  aria-label={`Edit ${ad.title}`}
                >
                  <Link href={`/ads/${ad.id}`}>편집</Link>
                </Button>
                <Button
                  type="button"
                  variant="outline"
                  onClick={() => handleDelete(ad)}
                  disabled={removing}
                  aria-label={`Delete ${ad.title}`}
                  title="이 광고 삭제"
                  className="border-destructive/35 text-destructive hover:bg-destructive/10"
                >
                  {removing ? "삭제 중…" : "✕ 제거"}
                </Button>
              </div>
            </li>
          );
        })}
      </ul>
    </>
  );
}

function statusBadgeVariant(
  status: AdStatus,
): "ok" | "warn" | "muted" {
  switch (status) {
    case "ACTIVE":
      return "ok";
    case "EXPIRED":
      return "warn";
    case "SCHEDULED":
      return "muted";
  }
}

export default MyAdsList;
