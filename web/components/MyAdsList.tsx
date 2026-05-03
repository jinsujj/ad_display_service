"use client";

/**
 * 내 광고 목록 — GET /api/ads 호출이 인증을 요구하므로 클라이언트 컴포넌트
 * 에서 토큰이 자동 첨부되는 apiFetch 를 통해 호출한다.
 *
 * 행마다:
 *   · 상태 pill (예정/송출 중/종료)
 *   · 제목 + 영상 파일명
 *   · 광고 ID, 일일 시간, 일일 횟수, 캠페인 기간
 *   · 편집 / ✕ 제거 액션
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
import { shortId, SHORT_ID_TITLE_HINT } from "@/lib/format";

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
        아직 만든 광고가 없습니다. 위의 &quot;새 광고 만들기&quot; 버튼으로 시작하세요.
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
        <col style={{ width: 168 }} />
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
        {state.ads.map((ad) => {
          const removing = removingId === ad.id;
          return (
            <tr key={ad.id}>
              <td>
                <span className={statusPillClass(ad.status)}>
                  {AD_STATUS_LABEL[ad.status]}
                </span>
              </td>
              <td>
                <div style={{ fontWeight: 600 }}>{ad.title}</div>
                <div
                  className="id-truncate"
                  title={ad.videoFilename}
                  style={{ marginTop: 4 }}
                >
                  {ad.videoFilename}
                </div>
              </td>
              <td className="id" title={`${ad.id} — ${SHORT_ID_TITLE_HINT}`}>
                <code>{shortId(ad.id)}</code>
              </td>
              <td style={{ whiteSpace: "nowrap" }}>
                {ad.startTime} ~ {ad.endTime}
              </td>
              <td>{ad.dailyPlayCount}회</td>
              <td className="muted" style={{ fontSize: 12, whiteSpace: "nowrap" }}>
                {ad.campaignStartDate}
                <br />~ {ad.campaignEndDate}
              </td>
              <td style={{ textAlign: "right", whiteSpace: "nowrap" }}>
                <Link className="btn" href={`/ads/${ad.id}`} aria-label={`Edit ${ad.title}`}>
                  편집
                </Link>{" "}
                <button
                  type="button"
                  className="btn"
                  onClick={() => handleDelete(ad)}
                  disabled={removing}
                  aria-label={`Delete ${ad.title}`}
                  title="이 광고 삭제"
                  style={{ color: "var(--err)", borderColor: "rgba(239,68,68,0.35)" }}
                >
                  {removing ? "삭제 중…" : "✕ 제거"}
                </button>
              </td>
            </tr>
          );
        })}
      </tbody>
    </table>
  );
}

function statusPillClass(status: AdStatus): string {
  switch (status) {
    case "ACTIVE":
      return "pill pill-ok";
    case "EXPIRED":
      return "pill pill-warn";
    case "SCHEDULED":
      return "pill";
  }
}

export default MyAdsList;
