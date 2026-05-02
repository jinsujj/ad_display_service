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
    <section>
      <div className="page-header">
        <div>
          <h1>Videos</h1>
          <div className="subtitle">
            Upload an MP4 ad and review every uploaded asset. The list below
            reflects the live backend state — newest uploads first.
          </div>
        </div>
      </div>

      <h2 className="section-heading">Uploaded videos</h2>
      {videosError && (
        <div className="notice notice-error" role="alert">
          Failed to load uploaded videos from backend: {videosError}
        </div>
      )}

      {!videosError && videos.length === 0 && (
        <div className="empty-state">
          No videos uploaded yet. Use the form below to upload your first
          MP4 — once it succeeds, refresh the page to see it listed here.
        </div>
      )}

      {!videosError && videos.length > 0 && (
        <VideosListTable videos={videos} />
      )}

      <h2 className="section-heading" style={{ marginTop: 32 }}>
        Upload video
      </h2>
      <p className="muted" style={{ marginTop: 0, marginBottom: 12 }}>
        MP4 only · 500 MiB cap · validated client-side before upload. On
        success the streaming URL appears in the success notice and the new
        row will show in the list above on next refresh.
      </p>

      <VideoUploadForm />

      <p className="muted" style={{ marginTop: 24 }}>
        Uploads land at <code>POST /api/videos</code> and the listing above
        is fetched from <code>GET /api/videos</code> on the Spring Boot
        backend; files are persisted under{" "}
        <code>/var/lib/adsignage/videos</code> on the host. Both endpoints
        require a JWT — store it under{" "}
        <code>localStorage.adsignage_auth_token</code> (the login UI lands in
        a sibling sub-AC).
      </p>
    </section>
  );
}

function describeError(err: unknown): string {
  if (err instanceof ApiError) {
    return `HTTP ${err.status} (${err.url})`;
  }
  if (err instanceof Error) return err.message;
  return "unknown error";
}
