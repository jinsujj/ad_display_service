"use client";

/**
 * 디바이스 상세 페이지 — 디바이스 메타 + 광고 큐 + 매핑 이력.
 *
 * 한 디바이스가 송출할 광고는 운영자가 큐에 *명시적으로* 담아야 한다.
 * 음식점 매핑은 "어디 설치됐는지" 위치 정보일 뿐, 광고 선정에는 영향을
 * 주지 않는다(V103 device_ad_queue 도입). 큐가 비면 디바이스는
 * splash.png 만 띄운다.
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
import {
  getDeviceDetail,
  type DeviceDetailResponse,
} from "@/lib/deviceDetail";
import { shortId } from "@/lib/format";

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

  useEffect(() => {
    let cancelled = false;
    getDeviceDetail(deviceId)
      .then((detail) => {
        if (!cancelled) setState({ kind: "ready", detail });
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
  }, [deviceId]);

  if (state.kind === "loading") {
    return <div className="muted">디바이스 정보를 불러오는 중…</div>;
  }
  if (state.kind === "not-found") {
    return (
      <div className="notice notice-error" role="alert">
        해당 디바이스를 찾을 수 없습니다.{" "}
        <Link href="/devices">디바이스 목록으로 돌아가기</Link>
      </div>
    );
  }
  if (state.kind === "error") {
    return (
      <div className="notice notice-error" role="alert">
        디바이스 정보를 불러오지 못했습니다: {state.message}
      </div>
    );
  }

  const d = state.detail;
  return (
    <>
      {/* 메타 배너 */}
      <div className="ad-id-banner" style={{ marginBottom: 12 }}>
        <div>
          <strong>{d.deviceName}</strong>{" "}
          {d.currentAssignment ? (
            <span className="pill pill-ok" style={{ marginLeft: 6 }}>
              위치 · {d.currentAssignment.restaurantName}
            </span>
          ) : (
            <span className="pill" style={{ marginLeft: 6 }}>
              미할당
            </span>
          )}
          <div className="muted" style={{ fontSize: 12, marginTop: 6 }}>
            디바이스 ID{" "}
            <code className="ad-id-banner__id" title={d.deviceId}>
              {shortId(d.deviceId)}
            </code>
          </div>
          <div className="muted" style={{ fontSize: 12 }}>
            등록일 {formatDate(d.registeredAt)}
            {d.lastSeenAt && <> · 마지막 활동 {formatDate(d.lastSeenAt)}</>}
          </div>
        </div>
      </div>

      <DeviceAdQueueSection deviceId={d.deviceId} />

      <h2 className="section-heading" style={{ marginTop: 28 }}>
        매핑 이력
      </h2>
      {d.history.length === 0 ? (
        <div className="empty-state">
          이 디바이스는 아직 어떤 음식점에도 매핑된 적이 없습니다.
        </div>
      ) : (
        <table className="data-table" aria-label="매핑 이력">
          <colgroup>
            <col style={{ width: 92 }} />
            <col />
            <col style={{ width: 200 }} />
          </colgroup>
          <thead>
            <tr>
              <th>상태</th>
              <th>음식점</th>
              <th>매핑 시각</th>
            </tr>
          </thead>
          <tbody>
            {d.history.map((h) => (
              <tr key={h.assignmentId}>
                <td>
                  {h.active ? (
                    <span className="pill pill-ok">활성</span>
                  ) : (
                    <span className="pill pill-muted">과거</span>
                  )}
                </td>
                <td>
                  <div style={{ fontWeight: 600 }}>{h.restaurantName}</div>
                  {h.address && (
                    <div className="muted" style={{ fontSize: 12, marginTop: 2 }}>
                      {h.address}
                    </div>
                  )}
                </td>
                <td className="muted" style={{ fontSize: 12, whiteSpace: "nowrap" }}>
                  {formatDate(h.assignedAt)}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      <p className="muted" style={{ marginTop: 16, fontSize: 12 }}>
        음식점 매핑은 디바이스가 어디 설치됐는지를 나타냅니다. 실제 송출되는
        광고는 위 "광고 큐" 에 담긴 광고들 중 캠페인이 활성인 것만 라운드 로빈
        으로 재생됩니다.
      </p>
    </>
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

  async function onRemove(adId: string) {
    if (!confirm("이 광고를 큐에서 제거할까요? 디바이스는 즉시 송출에서 제외됩니다.")) {
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
    <>
      <div
        style={{
          display: "flex",
          alignItems: "center",
          justifyContent: "space-between",
          marginTop: 12,
          marginBottom: 8,
        }}
      >
        <h2 className="section-heading" style={{ margin: 0 }}>
          광고 큐
        </h2>
        <button
          type="button"
          className="btn btn-primary"
          onClick={() => setPickerOpen(true)}
        >
          + 광고 추가
        </button>
      </div>
      <p className="muted" style={{ fontSize: 12, marginTop: 0, marginBottom: 12 }}>
        이 디바이스에서 송출할 광고를 운영자가 직접 골라 담습니다. 큐에 담긴
        광고 중 캠페인 기간이 활성인 것만 실제로 재생됩니다.
      </p>

      {queue.loading ? (
        <div className="muted">광고 큐를 불러오는 중…</div>
      ) : queue.error ? (
        <div className="notice notice-error" role="alert">
          광고 큐를 불러오지 못했습니다: {queue.error}
        </div>
      ) : queue.rows.length === 0 ? (
        <div className="empty-state">
          큐가 비어 있습니다. 위의 "+ 광고 추가" 버튼을 눌러 송출할 광고를 담아주세요.
        </div>
      ) : (
        <table className="data-table" aria-label="광고 큐">
          <colgroup>
            <col style={{ width: 92 }} />
            <col />
            <col style={{ width: 110 }} />
            <col style={{ width: 200 }} />
            <col style={{ width: 110 }} />
          </colgroup>
          <thead>
            <tr>
              <th>상태</th>
              <th>광고</th>
              <th>일일 횟수</th>
              <th>송출 시간대</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            {queue.rows.map((q) => (
              <tr key={q.adId}>
                <td>
                  <span
                    className={
                      q.status === "ACTIVE"
                        ? "pill pill-ok"
                        : q.status === "SCHEDULED"
                          ? "pill"
                          : "pill pill-muted"
                    }
                  >
                    {AD_STATUS_LABEL[q.status]}
                  </span>
                </td>
                <td>
                  <div style={{ fontWeight: 600 }}>
                    <Link href={`/ads/${q.adId}`}>{q.title}</Link>
                  </div>
                  <div className="muted" style={{ fontSize: 12, marginTop: 2 }}>
                    {q.campaignStartDate} ~ {q.campaignEndDate}
                  </div>
                </td>
                <td>{q.dailyPlayCount}회</td>
                <td className="muted" style={{ fontSize: 12, whiteSpace: "nowrap" }}>
                  {q.startTime} ~ {q.endTime}
                </td>
                <td>
                  <button
                    type="button"
                    className="btn btn-danger btn-sm"
                    disabled={busyAdId === q.adId}
                    onClick={() => onRemove(q.adId)}
                  >
                    {busyAdId === q.adId ? "제거 중…" : "큐에서 제거"}
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      {pickerOpen && (
        <AdPickerModal
          deviceId={deviceId}
          alreadyQueued={new Set(queue.rows.map((q) => q.adId))}
          onClose={() => setPickerOpen(false)}
          onAdded={async () => {
            await refresh();
          }}
        />
      )}
    </>
  );
}

/* --------------------------------------------------------------- 광고 picker */

function AdPickerModal({
  deviceId,
  alreadyQueued,
  onClose,
  onAdded,
}: {
  deviceId: string;
  alreadyQueued: Set<string>;
  onClose: () => void;
  onAdded: () => Promise<void>;
}) {
  const [ads, setAds] = useState<AdResponse[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busyAdId, setBusyAdId] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
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
  }, []);

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
    <div
      role="dialog"
      aria-modal="true"
      aria-labelledby="ad-picker-title"
      onClick={(e) => {
        if (e.target === e.currentTarget) onClose();
      }}
      style={{
        position: "fixed",
        inset: 0,
        background: "rgba(0,0,0,0.6)",
        display: "flex",
        alignItems: "center",
        justifyContent: "center",
        zIndex: 1000,
        padding: 16,
      }}
    >
      <div
        style={{
          background: "var(--bg-elev, #14171c)",
          border: "1px solid var(--border, #2a2f37)",
          borderRadius: 12,
          width: "min(720px, 100%)",
          maxHeight: "85vh",
          display: "flex",
          flexDirection: "column",
          overflow: "hidden",
        }}
      >
        <div
          style={{
            padding: "14px 18px",
            borderBottom: "1px solid var(--border, #2a2f37)",
            display: "flex",
            alignItems: "center",
            justifyContent: "space-between",
          }}
        >
          <h2
            id="ad-picker-title"
            style={{ margin: 0, fontSize: 16 }}
          >
            큐에 담을 광고 선택
          </h2>
          <button
            type="button"
            className="btn btn-ghost"
            onClick={onClose}
            aria-label="닫기"
          >
            ✕
          </button>
        </div>
        <div style={{ padding: 16, overflowY: "auto" }}>
          {ads === null && !error && (
            <div className="muted">광고 목록을 불러오는 중…</div>
          )}
          {error && (
            <div className="notice notice-error" role="alert">
              광고 목록을 불러오지 못했습니다: {error}
            </div>
          )}
          {ads && ads.length === 0 && (
            <div className="empty-state">
              아직 만든 광고가 없습니다. 먼저 <Link href="/ads/new">광고 만들기</Link>
              에서 광고를 등록해주세요.
            </div>
          )}
          {ads && ads.length > 0 && (
            <table className="data-table">
              <colgroup>
                <col style={{ width: 92 }} />
                <col />
                <col style={{ width: 110 }} />
                <col style={{ width: 130 }} />
              </colgroup>
              <thead>
                <tr>
                  <th>상태</th>
                  <th>광고</th>
                  <th>일일 횟수</th>
                  <th></th>
                </tr>
              </thead>
              <tbody>
                {ads.map((a) => {
                  const queued = alreadyQueued.has(a.id);
                  return (
                    <tr key={a.id}>
                      <td>
                        <span
                          className={
                            a.status === "ACTIVE"
                              ? "pill pill-ok"
                              : a.status === "SCHEDULED"
                                ? "pill"
                                : "pill pill-muted"
                          }
                        >
                          {AD_STATUS_LABEL[a.status]}
                        </span>
                      </td>
                      <td>
                        <div style={{ fontWeight: 600 }}>{a.title}</div>
                        <div
                          className="muted"
                          style={{ fontSize: 12, marginTop: 2 }}
                        >
                          {a.campaignStartDate} ~ {a.campaignEndDate} ·{" "}
                          {a.startTime}~{a.endTime}
                        </div>
                      </td>
                      <td>{a.dailyPlayCount}회</td>
                      <td>
                        {queued ? (
                          <span className="pill pill-muted">담김</span>
                        ) : (
                          <button
                            type="button"
                            className="btn btn-primary btn-sm"
                            disabled={busyAdId === a.id}
                            onClick={() => onPick(a.id)}
                          >
                            {busyAdId === a.id ? "추가 중…" : "큐에 담기"}
                          </button>
                        )}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          )}
        </div>
        <div
          style={{
            padding: "12px 18px",
            borderTop: "1px solid var(--border, #2a2f37)",
            display: "flex",
            justifyContent: "flex-end",
          }}
        >
          <button type="button" className="btn" onClick={onClose}>
            닫기
          </button>
        </div>
      </div>
    </div>
  );
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
