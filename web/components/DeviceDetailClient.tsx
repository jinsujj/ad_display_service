"use client";

/**
 * 디바이스 상세 페이지 — 디바이스 메타 + 광고 큐 + 매핑 이력.
 *
 * 한 디바이스가 송출할 광고는 운영자가 큐에 *명시적으로* 담아야 한다.
 * 음식점 매핑은 "어디 설치됐는지" 위치 정보일 뿐, 광고 선정에는 영향을
 * 주지 않는다(V103 device_ad_queue 도입). 큐가 비면 디바이스는
 * splash.png 만 띄운다.
 *
 * 큐/이력 테이블은 데스크탑(>=md) Table + 모바일(<md) 카드 듀얼 렌더,
 * 큐에 담을 광고 picker 는 shadcn Sheet(side="bottom") 로 통일 — 긴 리스트
 * 라 풀하이트 스크롤 + 터치 친화.
 */

import { useCallback, useEffect, useState } from "react";
import Link from "next/link";

import { ApiError } from "@/lib/api";
import {
  AD_STATUS_LABEL,
  listMyAds,
  type AdResponse,
} from "@/lib/ads";
import {
  addAdToQueue,
  listDeviceQueue,
  removeAdFromQueue,
  type QueuedAdItem,
} from "@/lib/deviceAdQueue";
import { useDataChanged } from "@/lib/dataEvents";
import {
  getDeviceDetail,
  type DeviceDetailResponse,
} from "@/lib/deviceDetail";
import { patchDevice } from "@/lib/devices";
import { shortId } from "@/lib/format";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import {
  Sheet,
  SheetContent,
  SheetHeader,
  SheetTitle,
} from "@/components/ui/sheet";
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
  | { kind: "ready"; detail: DeviceDetailResponse }
  | { kind: "not-found" }
  | { kind: "error"; message: string };

interface Props {
  deviceId: string;
}

export function DeviceDetailClient({ deviceId }: Props) {
  const [state, setState] = useState<State>({ kind: "loading" });

  const refetchDetail = useCallback(() => {
    getDeviceDetail(deviceId)
      .then((detail) => setState({ kind: "ready", detail }))
      .catch((err) => {
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
        setState((prev) =>
          prev.kind === "ready" ? prev : { kind: "error", message: msg },
        );
      });
  }, [deviceId]);

  useEffect(() => {
    refetchDetail();
  }, [refetchDetail]);

  useDataChanged(["device"], refetchDetail);

  if (state.kind === "loading") {
    return (
      <div className="text-sm text-muted-foreground">
        디바이스 정보를 불러오는 중…
      </div>
    );
  }
  if (state.kind === "not-found") {
    return (
      <Alert variant="destructive">
        <AlertDescription>
          해당 디바이스를 찾을 수 없습니다.{" "}
          <Link
            href="/devices"
            className="underline-offset-4 hover:underline"
          >
            디바이스 목록으로 돌아가기
          </Link>
        </AlertDescription>
      </Alert>
    );
  }
  if (state.kind === "error") {
    return (
      <Alert variant="destructive">
        <AlertDescription>
          디바이스 정보를 불러오지 못했습니다: {state.message}
        </AlertDescription>
      </Alert>
    );
  }

  const d = state.detail;
  return (
    <div className="space-y-6">
      <Card className="border-l-4 border-l-accent">
        <CardContent className="pt-4">
          <div className="flex flex-wrap items-baseline gap-2">
            <DeviceNameEditor
              deviceId={d.deviceId}
              currentName={d.deviceName}
              onSaved={refetchDetail}
            />
            {d.currentAssignment ? (
              <Badge variant="ok">
                위치 · {d.currentAssignment.restaurantName}
              </Badge>
            ) : (
              <Badge variant="muted">미할당</Badge>
            )}
          </div>
          <dl className="mt-2 space-y-1 text-xs text-muted-foreground">
            <div>
              디바이스 ID{" "}
              <code className="font-mono" title={d.deviceId}>
                {shortId(d.deviceId)}
              </code>
            </div>
            <div>
              등록일 {formatDate(d.registeredAt)}
              {d.lastSeenAt && <> · 마지막 활동 {formatDate(d.lastSeenAt)}</>}
            </div>
          </dl>
        </CardContent>
      </Card>

      <DeviceAdQueueSection deviceId={d.deviceId} />

      <section>
        <h2 className="mb-3 text-xs font-semibold uppercase tracking-wider text-muted-foreground">
          매핑 이력
        </h2>
        {d.history.length === 0 ? (
          <div className="rounded-lg border border-dashed border-border bg-card p-6 text-center text-sm text-muted-foreground">
            이 디바이스는 아직 어떤 음식점에도 매핑된 적이 없습니다.
          </div>
        ) : (
          <>
            <div className="hidden md:block w-full overflow-x-auto rounded-lg border border-border bg-card">
              <Table aria-label="매핑 이력">
                <TableHeader>
                  <TableRow>
                    <TableHead className="w-[92px]">상태</TableHead>
                    <TableHead className="min-w-[200px]">음식점</TableHead>
                    <TableHead className="w-[200px]">매핑 시각</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {d.history.map((h) => (
                    <TableRow key={h.assignmentId}>
                      <TableCell>
                        {h.active ? (
                          <Badge variant="ok">활성</Badge>
                        ) : (
                          <Badge variant="muted">과거</Badge>
                        )}
                      </TableCell>
                      <TableCell>
                        <div className="font-semibold">{h.restaurantName}</div>
                        {h.address && (
                          <div className="mt-0.5 text-xs text-muted-foreground">
                            {h.address}
                          </div>
                        )}
                      </TableCell>
                      <TableCell className="whitespace-nowrap text-xs text-muted-foreground">
                        {formatDate(h.assignedAt)}
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </div>
            <ul
              className="md:hidden flex flex-col gap-2.5"
              aria-label="매핑 이력 (모바일 보기)"
            >
              {d.history.map((h) => (
                <li
                  key={h.assignmentId}
                  className="rounded-lg border border-border bg-card p-3"
                >
                  <div className="flex items-baseline justify-between gap-2">
                    <strong>{h.restaurantName}</strong>
                    {h.active ? (
                      <Badge variant="ok">활성</Badge>
                    ) : (
                      <Badge variant="muted">과거</Badge>
                    )}
                  </div>
                  {h.address && (
                    <div className="mt-1 text-xs text-muted-foreground">
                      {h.address}
                    </div>
                  )}
                  <div className="mt-0.5 text-xs text-muted-foreground">
                    매핑 {formatDate(h.assignedAt)}
                  </div>
                </li>
              ))}
            </ul>
          </>
        )}
        <p className="mt-3 text-xs text-muted-foreground">
          음식점 매핑은 디바이스가 어디 설치됐는지를 나타냅니다. 실제 송출되는
          광고는 위 &quot;광고 큐&quot; 에 담긴 광고들 중 캠페인이 활성인
          것만 라운드 로빈으로 재생됩니다.
        </p>
      </section>
    </div>
  );
}

/* --------------------------------------------------------------- 큐 섹션 */

interface QueueState {
  loading: boolean;
  rows: QueuedAdItem[];
  error: string | null;
}

function DeviceAdQueueSection({ deviceId }: { deviceId: string }) {
  const [queue, setQueue] = useState<QueueState>({
    loading: true,
    rows: [],
    error: null,
  });
  const [pickerOpen, setPickerOpen] = useState(false);
  const [busyAdId, setBusyAdId] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    setQueue((s) => ({ ...s, loading: true, error: null }));
    try {
      const rows = await listDeviceQueue(deviceId);
      setQueue({ loading: false, rows, error: null });
    } catch (err) {
      const msg =
        err instanceof ApiError
          ? `HTTP ${err.status}`
          : err instanceof Error
            ? err.message
            : "알 수 없는 오류";
      setQueue({ loading: false, rows: [], error: msg });
    }
  }, [deviceId]);

  useEffect(() => {
    refresh();
  }, [refresh]);

  useDataChanged(["device-queue", "ad"], refresh);

  async function onRemove(adId: string) {
    if (
      !confirm(
        "이 광고를 큐에서 제거할까요? 디바이스는 즉시 송출에서 제외됩니다.",
      )
    ) {
      return;
    }
    setBusyAdId(adId);
    try {
      await removeAdFromQueue(deviceId, adId);
      await refresh();
    } catch (err) {
      const msg =
        err instanceof ApiError
          ? `HTTP ${err.status}`
          : err instanceof Error
            ? err.message
            : "알 수 없는 오류";
      alert(`제거 실패: ${msg}`);
    } finally {
      setBusyAdId(null);
    }
  }

  return (
    <section>
      <div className="mb-2 flex items-center justify-between gap-3">
        <h2 className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">
          광고 큐
        </h2>
        <Button
          type="button"
          size="sm"
          onClick={() => setPickerOpen(true)}
        >
          + 광고 추가
        </Button>
      </div>
      <p className="mb-3 text-xs text-muted-foreground">
        이 디바이스에서 송출할 광고를 운영자가 직접 골라 담습니다. 큐에 담긴
        광고 중 캠페인 기간이 활성인 것만 실제로 재생됩니다.
      </p>

      {queue.loading ? (
        <div className="text-sm text-muted-foreground">
          광고 큐를 불러오는 중…
        </div>
      ) : queue.error ? (
        <Alert variant="destructive">
          <AlertDescription>
            광고 큐를 불러오지 못했습니다: {queue.error}
          </AlertDescription>
        </Alert>
      ) : queue.rows.length === 0 ? (
        <div className="rounded-lg border border-dashed border-border bg-card p-6 text-center text-sm text-muted-foreground">
          큐가 비어 있습니다. 위의 &quot;+ 광고 추가&quot; 버튼을 눌러 송출할
          광고를 담아주세요.
        </div>
      ) : (
        <>
          <div className="hidden md:block w-full overflow-x-auto rounded-lg border border-border bg-card">
            <Table aria-label="광고 큐">
              <TableHeader>
                <TableRow>
                  <TableHead className="w-[92px]">상태</TableHead>
                  <TableHead className="min-w-[220px]">광고</TableHead>
                  <TableHead className="w-[110px]">일일 횟수</TableHead>
                  <TableHead className="w-[160px]">송출 시간대</TableHead>
                  <TableHead className="w-[140px] text-right">액션</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {queue.rows.map((q) => (
                  <TableRow key={q.adId}>
                    <TableCell>
                      <Badge variant={adStatusVariant(q.status)}>
                        {AD_STATUS_LABEL[q.status]}
                      </Badge>
                    </TableCell>
                    <TableCell>
                      <div className="font-semibold">
                        <Link
                          href={`/ads/${q.adId}`}
                          className="hover:text-accent"
                        >
                          {q.title}
                        </Link>
                      </div>
                      <div className="mt-0.5 text-xs text-muted-foreground">
                        {q.campaignStartDate} ~ {q.campaignEndDate}
                      </div>
                    </TableCell>
                    <TableCell>{q.dailyPlayCount}회</TableCell>
                    <TableCell className="whitespace-nowrap text-xs text-muted-foreground">
                      {q.startTime} ~ {q.endTime}
                    </TableCell>
                    <TableCell className="text-right">
                      <Button
                        type="button"
                        variant="outline"
                        size="sm"
                        disabled={busyAdId === q.adId}
                        onClick={() => onRemove(q.adId)}
                        className="border-destructive/35 text-destructive hover:bg-destructive/10"
                      >
                        {busyAdId === q.adId ? "제거 중…" : "큐에서 제거"}
                      </Button>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
          <ul
            className="md:hidden flex flex-col gap-2.5"
            aria-label="광고 큐 (모바일 보기)"
          >
            {queue.rows.map((q) => (
              <li
                key={q.adId}
                className="rounded-lg border border-border bg-card p-3"
              >
                <div className="flex items-baseline justify-between gap-2">
                  <Link
                    href={`/ads/${q.adId}`}
                    className="truncate font-semibold hover:text-accent"
                  >
                    {q.title}
                  </Link>
                  <Badge variant={adStatusVariant(q.status)}>
                    {AD_STATUS_LABEL[q.status]}
                  </Badge>
                </div>
                <div className="mt-1 text-sm">
                  {q.startTime}~{q.endTime} · {q.dailyPlayCount}회
                </div>
                <div className="text-xs text-muted-foreground">
                  캠페인 {q.campaignStartDate} ~ {q.campaignEndDate}
                </div>
                <Button
                  type="button"
                  variant="outline"
                  disabled={busyAdId === q.adId}
                  onClick={() => onRemove(q.adId)}
                  className="mt-3 w-full border-destructive/35 text-destructive hover:bg-destructive/10"
                >
                  {busyAdId === q.adId ? "제거 중…" : "큐에서 제거"}
                </Button>
              </li>
            ))}
          </ul>
        </>
      )}

      <AdPickerSheet
        open={pickerOpen}
        deviceId={deviceId}
        alreadyQueued={new Set(queue.rows.map((q) => q.adId))}
        onClose={() => setPickerOpen(false)}
        onAdded={async () => {
          await refresh();
        }}
      />
    </section>
  );
}

/* --------------------------------------------------------------- 광고 picker */

function AdPickerSheet({
  open,
  deviceId,
  alreadyQueued,
  onClose,
  onAdded,
}: {
  open: boolean;
  deviceId: string;
  alreadyQueued: Set<string>;
  onClose: () => void;
  onAdded: () => Promise<void>;
}) {
  const [ads, setAds] = useState<AdResponse[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busyAdId, setBusyAdId] = useState<string | null>(null);

  useEffect(() => {
    if (!open) return;
    let cancelled = false;
    setAds(null);
    setError(null);
    listMyAds()
      .then((rows) => {
        if (!cancelled) setAds(rows);
      })
      .catch((err) => {
        if (cancelled) return;
        const msg =
          err instanceof ApiError
            ? `HTTP ${err.status}`
            : err instanceof Error
              ? err.message
              : "알 수 없는 오류";
        setError(msg);
      });
    return () => {
      cancelled = true;
    };
  }, [open]);

  async function onPick(adId: string) {
    setBusyAdId(adId);
    try {
      await addAdToQueue(deviceId, adId);
      await onAdded();
    } catch (err) {
      const msg =
        err instanceof ApiError
          ? `HTTP ${err.status}`
          : err instanceof Error
            ? err.message
            : "알 수 없는 오류";
      alert(`추가 실패: ${msg}`);
    } finally {
      setBusyAdId(null);
    }
  }

  return (
    <Sheet
      open={open}
      onOpenChange={(o) => {
        if (!o) onClose();
      }}
    >
      <SheetContent
        side="bottom"
        className="max-h-[90dvh] overflow-y-auto"
      >
        <SheetHeader>
          <SheetTitle>큐에 담을 광고 선택</SheetTitle>
        </SheetHeader>
        <div className="mt-4">
          {ads === null && !error && (
            <div className="text-sm text-muted-foreground">
              광고 목록을 불러오는 중…
            </div>
          )}
          {error && (
            <Alert variant="destructive">
              <AlertDescription>
                광고 목록을 불러오지 못했습니다: {error}
              </AlertDescription>
            </Alert>
          )}
          {ads && ads.length === 0 && (
            <div className="rounded-lg border border-dashed border-border bg-card p-6 text-center text-sm text-muted-foreground">
              아직 만든 광고가 없습니다. 먼저{" "}
              <Link
                href="/ads/new"
                className="text-accent underline-offset-4 hover:underline"
              >
                광고 만들기
              </Link>
              에서 광고를 등록해주세요.
            </div>
          )}
          {ads && ads.length > 0 && (
            <ul className="flex flex-col gap-2.5">
              {ads.map((a) => {
                const queued = alreadyQueued.has(a.id);
                return (
                  <li
                    key={a.id}
                    className="rounded-lg border border-border bg-card p-3"
                  >
                    <div className="flex items-baseline justify-between gap-2">
                      <strong className="truncate">{a.title}</strong>
                      <Badge variant={adStatusVariant(a.status)}>
                        {AD_STATUS_LABEL[a.status]}
                      </Badge>
                    </div>
                    <div className="mt-1 text-xs text-muted-foreground">
                      {a.campaignStartDate} ~ {a.campaignEndDate} ·{" "}
                      {a.startTime}~{a.endTime} · {a.dailyPlayCount}회/일
                    </div>
                    <div className="mt-3">
                      {queued ? (
                        <Badge variant="muted">담김</Badge>
                      ) : (
                        <Button
                          type="button"
                          size="sm"
                          disabled={busyAdId === a.id}
                          onClick={() => onPick(a.id)}
                        >
                          {busyAdId === a.id ? "추가 중…" : "큐에 담기"}
                        </Button>
                      )}
                    </div>
                  </li>
                );
              })}
            </ul>
          )}
        </div>
      </SheetContent>
    </Sheet>
  );
}

/* ----------------------------------------------------- 디바이스 별칭 편집 */

/**
 * 디바이스 이름 인라인 편집. 평소엔 strong 으로 큼지막하게 표시, ✎ 버튼
 * 누르면 input 으로 전환. Enter 저장 / Esc 취소.
 *
 * 자동 생성된 이름 ("Google sdk_gphone16k_arm64 (Android 15) · …") 대신
 * 운영 친화적 별칭 ("강남 1호점") 으로 식별 가능.
 */
function DeviceNameEditor({
  deviceId,
  currentName,
  onSaved,
}: {
  deviceId: string;
  currentName: string;
  onSaved: () => void;
}) {
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState(currentName);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  function open() {
    setDraft(currentName);
    setError(null);
    setEditing(true);
  }
  function cancel() {
    setEditing(false);
    setError(null);
  }
  async function save() {
    const trimmed = draft.trim();
    if (!trimmed) {
      setError("이름은 1자 이상이어야 합니다.");
      return;
    }
    if (trimmed === currentName) {
      setEditing(false);
      return;
    }
    setBusy(true);
    setError(null);
    try {
      await patchDevice(deviceId, { deviceName: trimmed });
      setEditing(false);
      onSaved();
    } catch (err) {
      const msg =
        err instanceof ApiError
          ? `HTTP ${err.status}`
          : err instanceof Error
            ? err.message
            : "알 수 없는 오류";
      setError(msg);
    } finally {
      setBusy(false);
    }
  }

  if (!editing) {
    return (
      <span className="inline-flex items-center gap-2">
        <strong className="text-base">{currentName || "(이름 없음)"}</strong>
        <Button
          type="button"
          variant="outline"
          size="sm"
          onClick={open}
          aria-label="별칭 편집"
          title="이 디바이스의 별칭을 수정"
        >
          ✎ 별칭
        </Button>
      </span>
    );
  }

  return (
    <span className="inline-flex flex-wrap items-center gap-1.5">
      <Input
        type="text"
        value={draft}
        autoFocus
        disabled={busy}
        maxLength={255}
        onChange={(e) => setDraft(e.target.value)}
        onKeyDown={(e) => {
          if (e.key === "Enter") {
            e.preventDefault();
            save();
          } else if (e.key === "Escape") {
            e.preventDefault();
            cancel();
          }
        }}
        aria-label="디바이스 별칭"
        placeholder="예: 강남 1호점"
        className="min-w-[200px] max-w-xs h-9 font-semibold"
      />
      <Button type="button" size="sm" onClick={save} disabled={busy}>
        {busy ? "저장 중…" : "저장"}
      </Button>
      <Button
        type="button"
        variant="outline"
        size="sm"
        onClick={cancel}
        disabled={busy}
      >
        취소
      </Button>
      {error && (
        <span role="alert" className="text-xs text-destructive">
          {error}
        </span>
      )}
    </span>
  );
}

function adStatusVariant(
  status: AdResponse["status"],
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

function formatDate(iso: string): string {
  try {
    const d = new Date(iso);
    if (Number.isNaN(d.getTime())) return iso;
    return d.toLocaleString("ko-KR");
  } catch {
    return iso;
  }
}

export default DeviceDetailClient;
