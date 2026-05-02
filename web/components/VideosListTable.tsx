/**
 * Uploaded videos list table (AC 40104, Sub-AC 4).
 *
 * Goal:
 *   "Frontend - Implement uploaded videos list view in admin UI that fetches
 *    and displays video metadata from backend GET /api/videos endpoint."
 *
 * What this component does:
 *
 *   1. Renders one row per [VideoListItem] — original filename, server-side
 *      filename, MIME type, size, upload time, and a "Play" link to the
 *      streaming URL. The shape mirrors the columns surfaced in the
 *      [VideoResponse] DTO so an operator can correlate the table with the
 *      backend log line.
 *
 *   2. Pretty-prints byte counts via [formatBytes] (binary IEC suffixes —
 *      MiB / GiB) and timestamps via [formatUploadedAt] (locale string,
 *      falling back to the raw ISO string if `Date` rejects it).
 *
 *   3. The "Play" link uses [apiUrl] so it works both when the admin web is
 *      same-origin behind nginx (`stream.owl-dev.me`) and during local dev
 *      where the backend is at a separate host
 *      (`NEXT_PUBLIC_API_BASE_URL=http://192.168.0.24:8080`). `target="_blank"`
 *      keeps the admin tab intact while the operator previews a video.
 *
 * Why a Server Component (no "use client"):
 *   The table is read-only — no event handlers, no state. The parent page
 *   (Server Component) fetches `GET /api/videos` and hands us the rows; we
 *   render them as static HTML. Keeping this off the client bundle saves
 *   ~hundreds of bytes and means the table renders before any JS hydrates.
 */

import { apiUrl } from "@/lib/api";
import { formatBytes, type VideoListItem } from "@/lib/videos";

interface VideosListTableProps {
  videos: VideoListItem[];
}

export function VideosListTable({ videos }: VideosListTableProps) {
  return (
    <table className="data-table">
      <thead>
        <tr>
          <th>Original name</th>
          <th>Filename</th>
          <th>Type</th>
          <th>Size</th>
          <th>Uploaded</th>
          <th>Stream</th>
        </tr>
      </thead>
      <tbody>
        {videos.map((video) => (
          <tr key={video.id || video.filename}>
            <td>
              <strong>{video.originalName || "—"}</strong>
              {video.id && (
                <div className="muted" style={{ fontSize: 11 }}>
                  id <code>{video.id}</code>
                </div>
              )}
            </td>
            <td className="id">{video.filename || "—"}</td>
            <td>{video.mimeType || "—"}</td>
            <td>{formatBytes(video.sizeBytes)}</td>
            <td>{formatUploadedAt(video.uploadedAt)}</td>
            <td>
              {video.url ? (
                <a
                  href={apiUrl(video.url)}
                  target="_blank"
                  rel="noopener noreferrer"
                >
                  Play ↗
                </a>
              ) : (
                <span className="muted">—</span>
              )}
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

/**
 * Pretty-print a server-supplied ISO-8601 timestamp.
 *
 * - Falls through to the raw string if `Date.parse` fails (e.g. a backend
 *   that one day starts emitting epoch-seconds) so the operator sees
 *   *something* instead of an "Invalid Date" placeholder.
 * - Uses the browser/server locale's default short date+time formatting,
 *   which is enough resolution for an operator scanning "did my upload
 *   from 5 minutes ago show up?".
 */
function formatUploadedAt(value: string | null | undefined): string {
  if (!value) return "—";
  const ts = Date.parse(value);
  if (!Number.isFinite(ts)) return value;
  const d = new Date(ts);
  return d.toLocaleString();
}

export default VideosListTable;
