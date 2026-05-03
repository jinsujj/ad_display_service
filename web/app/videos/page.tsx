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
      <section>
        <div className="page-header">
          <div>
            <h1>영상</h1>
            <div className="subtitle">
              MP4 광고 영상을 업로드하고 업로드된 모든 자산을 확인합니다.
              아래 목록은 실시간 백엔드 상태이며 최신 업로드가 위에 표시됩니다.
            </div>
          </div>
        </div>

        <h2 className="section-heading">업로드된 영상</h2>
        <MyVideosList />

        <h2 className="section-heading" style={{ marginTop: 32 }}>
          영상 업로드
        </h2>
        <p className="muted" style={{ marginTop: 0, marginBottom: 12 }}>
          MP4 전용 · 최대 500 MiB · 업로드 전 클라이언트에서 사전 검증.
          성공 시 알림에 스트리밍 URL이 표시되며, 새 행은 다음 새로고침 시
          위 목록에 반영됩니다.
        </p>

        <VideoUploadForm />

        <p className="muted" style={{ marginTop: 24 }}>
          업로드는 <code>POST /api/videos</code>, 목록은 <code>GET /api/videos</code>
          (둘 다 stream-backend.owl-dev.me)로 호출됩니다. 파일은 호스트의{" "}
          <code>/var/lib/adsignage/videos</code> 에 저장됩니다.
        </p>
      </section>
    </AuthGuard>
  );
}
