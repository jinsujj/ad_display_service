/**
 * 영상 어드민 페이지 (`/videos`).
 *
 * AC 40104, Sub-AC 3:
 *   "Build Next.js admin upload page with file input form, MP4 client-side
 *    validation, and multipart POST request to backend upload API with
 *    progress indication."
 *
 * AC 40104, Sub-AC 4:
 *   "Frontend - Implement uploaded videos list view in admin UI that fetches
 *    and displays video metadata from backend GET /api/videos endpoint."
 *
 * 구현 메모:
 *   - 서버 컴포넌트 셸: `GET /api/videos`를 서버에서 fetch하므로 운영자는
 *     클라이언트 측 로딩 스피너 없이 즉시 렌더링된 테이블을 본다.
 *     인터랙티브 업로드 폼은 클라이언트 컴포넌트 [VideoUploadForm]에 위임.
 *   - `dynamic = "force-dynamic"`이므로 배포 사이에 Next 데이터 캐시에서
 *     stale Static 페이지를 서빙하지 않는다.
 *   - 목록 엔드포인트의 오류는 캐치되어 인라인으로 노출되므로, 목록
 *     엔드포인트가 다운되어도 아래 업로드 폼이 나머지 어드민 워크플로에서
 *     사용 가능한 상태를 유지한다.
 *   - 목록은 의도적으로 업로드 폼 *위*에 위치하여 스크롤하는 운영자가
 *     역사적 명단을 먼저 본다. 성공한 업로드 후 폼은 파일 입력을
 *     비우지만 목록은 자동 새로고침하지 않는다(router.refresh는 클라이언트
 *     컴포넌트의 관심사). 실무적으로 성공 알림이 즉시 새 id + 스트리밍
 *     링크를 노출하고, 수동 페이지 리로드가 새 행을 맨 위로 가져오므로
 *     해커톤 데모에 충분하다.
 */

import { ApiError } from "@/lib/api";
import { listVideos, type VideoListItem } from "@/lib/videos";
import { VideoUploadForm } from "@/components/VideoUploadForm";
import { VideosListTable } from "@/components/VideosListTable";
import { AuthGuard } from "@/components/AuthGuard";

export const dynamic = "force-dynamic";

export const metadata = {
  title: "Videos · AdSignage Admin",
};

export default async function VideosPage() {
  let videos: VideoListItem[] = [];
  let videosError: string | null = null;

  try {
    videos = await listVideos();
  } catch (err) {
    videosError = describeError(err);
  }

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
      {videosError && (
        <div className="notice notice-error" role="alert">
          백엔드에서 영상 목록을 불러오지 못했습니다: {videosError}
        </div>
      )}

      {!videosError && videos.length === 0 && (
        <div className="empty-state">
          아직 업로드된 영상이 없습니다. 아래 폼에서 첫 MP4를 업로드하고,
          업로드가 끝나면 페이지를 새로고침해 목록을 확인하세요.
        </div>
      )}

      {!videosError && videos.length > 0 && (
        <VideosListTable videos={videos} />
      )}

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
        업로드는 Spring Boot 백엔드의 <code>POST /api/videos</code> 로 전송되고,
        위 목록은 <code>GET /api/videos</code> 에서 가져옵니다. 파일은 호스트의{" "}
        <code>/var/lib/adsignage/videos</code> 에 저장됩니다. 두 엔드포인트
        모두 JWT 인증이 필요하며, 토큰은 로그인 시
        <code>localStorage.adsignage_auth_token</code> 에 저장됩니다.
      </p>
    </section>
    </AuthGuard>
  );
}

function describeError(err: unknown): string {
  if (err instanceof ApiError) {
    return `HTTP ${err.status} (${err.url})`;
  }
  if (err instanceof Error) return err.message;
  return "알 수 없는 오류";
}
