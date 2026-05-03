"use client";

/**
 * 내 광고 목록 — `GET /api/ads` 호출이 인증을 요구하므로 클라이언트 컴포넌트
 * 에서 토큰이 자동 첨부되는 apiFetch를 통해 호출한다.
 */

import { useEffect, useState } from "react";
import Link from "next/link";

import { ApiError } from "@/lib/api";
import { AD_STATUS_LABEL, listMyAds, type AdResponse, type AdStatus } from "@/lib/ads";

type State =
  | { kind: "loading" }
  | { kind: "ready"; ads: AdResponse[] }
  | { kind: "error"; message: string };

export function MyAdsList() {
  const [state, setState] = useState<State>({ kind: "loading" });

  useEffect(() => {
    let cancelled = false;
    listMyAds()
      .then((ads) => {
        if (!cancelled) setState({ kind: "ready", ads });
      })
      .catch((err) => {
        if (cancelled) return;
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
  }, []);

  if (state.kind === "loading") {
    return <div className="muted">광고 목록을 불러오는 중…</div>;
  }
  if (state.kind === "error") {
    return (
      <div className="notice notice-error" role="alert">
        광고 목록을 불러오지 못했습니다: {state.message}
      </div>
    );
  }
  if (state.ads.length === 0) {
    return (
      <div className="empty-state">
        아직 만든 광고가 없습니다. 위의 "새 광고 만들기" 버튼으로 시작하세요.
      </div>
    );
  }
  return (
    <table className="data-table" aria-label="내 광고 목록">
      <colgroup>
        <col style={{ width: 92 }} />
        <col />
        <col style={{ width: 200 }} />
        <col style={{ width: 130 }} />
        <col style={{ width: 92 }} />
        <col style={{ width: 150 }} />
        <col style={{ width: 92 }} />
      </colgroup>
      <thead>
        <tr>
          <th>상태</th>
          <th>제목</th>
          <th>광고 ID</th>
          <th>일일 시간</th>
          <th>일일 횟수</th>
          <th>캠페인 기간</th>
          <th></th>
        </tr>
      </thead>
      <tbody>
        {state.ads.map((ad) => (
          <tr key={ad.id}>
            <td>
              <span className={statusPillClass(ad.status)}>
                {AD_STATUS_LABEL[ad.status]}
              </span>
            </td>
            <td>
              <div style={{ fontWeight: 600 }}>{ad.title}</div>
              <div className="id-truncate" title={ad.videoFilename} style={{ marginTop: 4 }}>
                {ad.videoFilename}
              </div>
            </td>
            <td className="id" title={ad.id}>
              <code>{ad.id}</code>
            </td>
            <td style={{ whiteSpace: "nowrap" }}>{ad.startTime} ~ {ad.endTime}</td>
            <td>{ad.dailyPlayCount}회</td>
            <td className="muted" style={{ fontSize: 12, whiteSpace: "nowrap" }}>
              {ad.campaignStartDate}<br />~ {ad.campaignEndDate}
            </td>
            <td style={{ textAlign: "right" }}>
              <Link className="btn" href={`/ads/${ad.id}`}>편집</Link>
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

function statusPillClass(status: AdStatus): string {
  switch (status) {
    case "ACTIVE": return "pill pill-ok";
    case "EXPIRED": return "pill pill-warn";
    case "SCHEDULED": return "pill";
  }
}

export default MyAdsList;
