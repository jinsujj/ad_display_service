"use client";

/**
 * 내 영상 목록 — `GET /api/videos` 가 인증을 요구하므로 클라이언트
 * 컴포넌트에서 토큰이 자동 첨부되는 apiFetch를 통해 호출한다.
 *
 * 페이지가 서버 컴포넌트로 SSR fetch를 시도하면 localStorage 가 없어 401이
 * 떨어지므로, 목록만 클라이언트 사이드로 분리.
 */

import { useCallback, useEffect, useState } from "react";

import { ApiError } from "@/lib/api";
import { useDataChanged } from "@/lib/dataEvents";
import { listVideos, type VideoListItem } from "@/lib/videos";
import { VideosListTable } from "./VideosListTable";
import { Alert, AlertDescription } from "@/components/ui/alert";

type State =
  | { kind: "loading" }
  | { kind: "ready"; videos: VideoListItem[] }
  | { kind: "error"; message: string };

export function MyVideosList() {
  const [state, setState] = useState<State>({ kind: "loading" });

  const refetch = useCallback(() => {
    listVideos()
      .then((videos) => setState({ kind: "ready", videos }))
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

  // 영상 업로드/삭제 시 자동 갱신. 광고 변경도 영상 사용 여부를 바꾸므로 함께 listen.
  useDataChanged(["video", "ad"], refetch);

  if (state.kind === "loading") {
    return (
      <div className="text-sm text-muted-foreground">
        영상 목록을 불러오는 중…
      </div>
    );
  }
  if (state.kind === "error") {
    return (
      <Alert variant="destructive">
        <AlertDescription>
          영상 목록을 불러오지 못했습니다: {state.message}
        </AlertDescription>
      </Alert>
    );
  }
  if (state.videos.length === 0) {
    return (
      <div className="rounded-lg border border-dashed border-border bg-card p-8 text-center text-sm text-muted-foreground">
        아직 업로드된 영상이 없습니다. 아래 폼에서 첫 MP4를 업로드하세요.
      </div>
    );
  }
  return <VideosListTable videos={state.videos} />;
}

export default MyVideosList;
