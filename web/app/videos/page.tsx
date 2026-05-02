/**
 * Videos admin page (`/videos`).
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
 * Implementation notes:
 *   - Server Component shell: fetches `GET /api/videos` on the server so the
 *     operator gets an immediately-rendered table with no client-side
 *     loading spinners. The interactive upload form is delegated to
 *     [VideoUploadForm], a Client Component.
 *   - `dynamic = "force-dynamic"` so we don't serve a stale Static page from
 *     the Next data cache between deploys.
 *   - Errors from the list endpoint are caught and surfaced inline so the
 *     upload form below stays usable for the rest of the admin workflow
 *     even if the list endpoint is down.
 *   - The list intentionally lives *above* the upload form so a scrolling
 *     operator sees the historical roster first; after a successful upload
 *     the form clears its file input but the list does not auto-refresh
 *     (router.refresh is a Client Component concern). In practice the
 *     success notice surfaces the new id + streaming link immediately, and
 *     a manual page reload pulls the new row to the top — sufficient for
 *     the hackathon demo.
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
