"use client";

/**
 * 내 영상 목록 — `GET /api/videos` 가 인증을 요구하므로 클라이언트
 * 컴포넌트에서 토큰이 자동 첨부되는 apiFetch를 통해 호출한다.
 *
 * 페이지가 서버 컴포넌트로 SSR fetch를 시도하면 localStorage 가 없어 401이
 * 떨어지므로, 목록만 클라이언트 사이드로 분리.
 */

import { useEffect, useState } from "react";

import { ApiError } from "@/lib/api";
import { listVideos, type VideoListItem } from "@/lib/videos";
import { VideosListTable } from "./VideosListTable";

type State =
  | { kind: "loading" }
  | { kind: "ready"; videos: VideoListItem[] }
  | { kind: "error"; message: string };

export function MyVideosList() {
  const [state, setState] = useState<State>({ kind: "loading" });

  useEffect(() => {
    let cancelled = false;
    listVideos()
      .then((videos) => {
        if (!cancelled) setState({ kind: "ready", videos });
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
    return <div className="muted">영상 목록을 불러오는 중…</div>;
  }
  if (state.kind === "error") {
    return (
      <div className="notice notice-error" role="alert">
        영상 목록을 불러오지 못했습니다: {state.message}
      </div>
    );
  }
  if (state.videos.length === 0) {
    return (
      <div className="empty-state">
        아직 업로드된 영상이 없습니다. 아래 폼에서 첫 MP4를 업로드하세요.
      </div>
    );
  }
  return <VideosListTable videos={state.videos} />;
}

export default MyVideosList;
