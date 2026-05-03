"use client";

/**
 * 내 광고 목록 — `GET /api/ads` 호출이 인증을 요구하므로 클라이언트 컴포넌트
 * 에서 토큰이 자동 첨부되는 apiFetch를 통해 호출한다.
 */

import { useEffect, useState } from "react";
import Link from "next/link";

import { ApiError } from "@/lib/api";
import { listMyAds, type AdResponse } from "@/lib/ads";

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
    <table className="data-table">
      <thead>
        <tr>
          <th>제목</th>
          <th>광고 ID</th>
          <th>영상 파일명</th>
          <th>스케줄</th>
          <th>일일 횟수</th>
          <th>편집</th>
        </tr>
      </thead>
      <tbody>
        {state.ads.map((ad) => (
          <tr key={ad.id}>
            <td><strong>{ad.title}</strong></td>
            <td className="id" style={{ userSelect: "all" }}>
              <code>{ad.id}</code>
            </td>
            <td className="id">{ad.videoFilename}</td>
            <td>{ad.startTime} ~ {ad.endTime}</td>
            <td>{ad.dailyPlayCount}</td>
            <td>
              <Link className="btn" href={`/ads/${ad.id}`}>스케줄 편집 ↗</Link>
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

export default MyAdsList;
