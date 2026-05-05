/**
 * 영상 어드민 페이지 (`/videos`).
 *
 * - `MyVideosList` (클라이언트 컴포넌트) 가 GET /api/videos 를 호출.
 *   localStorage의 JWT 가 자동으로 헤더에 첨부됨.
 * - 업로드 폼은 `VideoUploadForm` (클라이언트 컴포넌트).
 * - `AuthGuard` 가 미인증 사용자를 /login 으로 보냄.
 *
 * 이전엔 서버 컴포넌트에서 SSR fetch 했는데, 그 단계엔 localStorage 가 없어
 * 토큰을 못 실어 401 이 떨어졌다 — 그래서 클라이언트 사이드로 옮김.
 */

import { AuthGuard } from "@/components/AuthGuard";
import { VideoUploadForm } from "@/components/VideoUploadForm";
import { MyVideosList } from "@/components/MyVideosList";

export const dynamic = "force-dynamic";

export const metadata = {
  title: "영상 · AdSignage 어드민",
};

export default function VideosPage() {
  return (
    <AuthGuard>
      <section className="space-y-6">
        <header>
          <h1 className="text-2xl font-semibold tracking-tight">영상</h1>
          <p className="mt-1 text-sm text-muted-foreground">
            MP4 광고 영상을 업로드하고 업로드된 모든 자산을 확인합니다. 아래
            목록은 실시간 백엔드 상태이며 최신 업로드가 위에 표시됩니다.
          </p>
        </header>

        <section>
          <h2 className="mb-3 text-xs font-semibold uppercase tracking-wider text-muted-foreground">
            업로드된 영상
          </h2>
          <MyVideosList />
        </section>

        <section>
          <h2 className="mb-1 text-xs font-semibold uppercase tracking-wider text-muted-foreground">
            영상 업로드
          </h2>
          <p className="mb-3 text-sm text-muted-foreground">
            MP4 전용 · 최대 500 MiB · 업로드 전 클라이언트에서 사전 검증.
            성공 시 알림에 스트리밍 URL이 표시되며, 새 행은 다음 새로고침
            시 위 목록에 반영됩니다.
          </p>
          <VideoUploadForm />
          <p className="mt-6 text-xs text-muted-foreground">
            업로드는 <code className="font-mono">POST /api/videos</code>,
            목록은 <code className="font-mono">GET /api/videos</code> (둘 다
            stream-backend.owl-dev.me)로 호출됩니다. 파일은 호스트의{" "}
            <code className="font-mono">/var/lib/adsignage/videos</code> 에
            저장됩니다.
          </p>
        </section>
      </section>
    </AuthGuard>
  );
}
