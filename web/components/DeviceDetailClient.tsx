"use client";

/**
 * 디바이스 상세 페이지 — 디바이스 메타 + 매핑 이력.
 *
 * 한 디바이스가 거쳐 온 모든 음식점이 시간순으로 나열되며, 현재 활성
 * 매핑은 'pill-ok' 배지로 표시. 운영자는 이 페이지에서 디바이스가 어디서
 * 어디로 이동했는지 흐름을 한눈에 본다.
 *
 * 한 디바이스가 동시에 여러 음식점에 매핑되는 모델은 아니지만(물리적으로
 * 한 위치 거치), 이력으로는 여러 음식점에 거쳐 갔음을 보여준다.
 */

import { useEffect, useState } from "react";
import Link from "next/link";

import { ApiError } from "@/lib/api";
import {
  getDeviceDetail,
  type DeviceDetailResponse,
} from "@/lib/deviceDetail";

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
              송출 중 · {d.currentAssignment.restaurantName}
            </span>
          ) : (
            <span className="pill" style={{ marginLeft: 6 }}>
              미할당
            </span>
          )}
          <div className="muted" style={{ fontSize: 12, marginTop: 6 }}>
            디바이스 ID <code className="ad-id-banner__id">{d.deviceId}</code>
          </div>
          <div className="muted" style={{ fontSize: 12 }}>
            등록일 {formatDate(d.registeredAt)}
            {d.lastSeenAt && <> · 마지막 활동 {formatDate(d.lastSeenAt)}</>}
          </div>
        </div>
      </div>

      <h2 className="section-heading">매핑 이력</h2>
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
        한 디바이스는 한 시점에 한 음식점에서만 송출됩니다. 이력은
        "거쳐 간 음식점들"을 보여주며, 광고 자체는 활성 매핑된 *모든* 디바이스에
        라운드 로빈으로 송출되므로 한 광고가 여러 음식점/디바이스에서 동시
        송출됩니다.
      </p>
    </>
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
