/**
 * 업로드된 영상 목록 테이블 (AC 40104, Sub-AC 4).
 *
 * 목표:
 *   "Frontend - Implement uploaded videos list view in admin UI that fetches
 *    and displays video metadata from backend GET /api/videos endpoint."
 *
 * 이 컴포넌트가 하는 일:
 *
 *   1. [VideoListItem]당 한 행 렌더 — 원본 파일명, 서버 측 파일명, MIME
 *      타입, 크기, 업로드 시각, 스트리밍 URL로의 "Play" 링크. 형태는
 *      [VideoResponse] DTO에 노출된 컬럼을 미러링하므로 운영자가 테이블을
 *      백엔드 로그 라인과 상관시킬 수 있다.
 *
 *   2. [formatBytes](이진 IEC 접미사 — MiB / GiB)로 바이트 카운트를,
 *      [formatUploadedAt](로케일 문자열, `Date`가 거절하면 raw ISO 문자열로
 *      폴백)로 타임스탬프를 예쁘게 출력.
 *
 *   3. "Play" 링크는 [apiUrl]을 사용하므로 어드민 웹이 nginx 뒤에서 같은
 *      origin(`stream.owl-dev.me`)일 때와 백엔드가 별도 호스트에 있는 로컬
 *      개발(`NEXT_PUBLIC_API_BASE_URL=http://192.168.0.24:8080`) 둘 다에서
 *      동작한다. `target="_blank"`은 운영자가 영상을 미리 볼 때 어드민
 *      탭을 그대로 유지한다.
 *
 * 왜 서버 컴포넌트인가("use client" 없음):
 *   테이블은 읽기 전용 — 이벤트 핸들러도, 상태도 없다. 부모 페이지(서버
 *   컴포넌트)가 `GET /api/videos`를 fetch해 행을 우리에게 넘기고, 우리는
 *   정적 HTML로 렌더한다. 클라이언트 번들 밖에 두면 수백 바이트가 절약되고,
 *   JS가 하이드레이트되기 전에 테이블이 렌더된다.
 */

import Link from "next/link";

import { apiUrl } from "@/lib/api";
import { formatBytes, type VideoListItem } from "@/lib/videos";

interface VideosListTableProps {
  videos: VideoListItem[];
}

export function VideosListTable({ videos }: VideosListTableProps) {
  return (
    <table className="data-table" aria-label="업로드된 영상 목록">
      <colgroup>
        <col />
        <col style={{ width: 220 }} />
        <col style={{ width: 92 }} />
        <col style={{ width: 92 }} />
        <col style={{ width: 160 }} />
        <col style={{ width: 80 }} />
        <col style={{ width: 132 }} />
      </colgroup>
      <thead>
        <tr>
          <th>원본 파일명</th>
          <th>저장 파일명</th>
          <th>타입</th>
          <th>크기</th>
          <th>업로드</th>
          <th></th>
          <th></th>
        </tr>
      </thead>
      <tbody>
        {videos.map((video) => (
          <tr key={video.id || video.filename}>
            <td>
              <div style={{ fontWeight: 600 }}>{video.originalName || "—"}</div>
              {video.id && (
                <div
                  className="id-truncate muted"
                  title={video.id}
                  style={{ fontSize: 11, marginTop: 4 }}
                >
                  ID <code>{video.id}</code>
                </div>
              )}
            </td>
            <td className="id" title={video.filename || ""}>
              {video.filename || "—"}
            </td>
            <td className="muted">{video.mimeType || "—"}</td>
            <td>{formatBytes(video.sizeBytes)}</td>
            <td className="muted" style={{ fontSize: 12 }}>
              {formatUploadedAt(video.uploadedAt)}
            </td>
            <td>
              {video.url ? (
                <a
                  href={apiUrl(video.url)}
                  target="_blank"
                  rel="noopener noreferrer"
                >
                  재생 ↗
                </a>
              ) : (
                <span className="muted">—</span>
              )}
            </td>
            <td style={{ textAlign: "right" }}>
              {video.filename ? (
                <Link
                  className="btn"
                  href={{
                    pathname: "/ads/new",
                    query: {
                      videoFilename: video.filename,
                      originalName: video.originalName ?? "",
                      title: video.originalName?.replace(/\.[^.]+$/, "") ?? "",
                    },
                  }}
                >
                  광고 만들기
                </Link>
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
 * 서버가 제공한 ISO-8601 타임스탬프를 예쁘게 출력.
 *
 * - `Date.parse`가 실패하면 raw 문자열로 폴스루(예: 백엔드가 어느 날
 *   epoch-seconds를 발행하기 시작하면) — 운영자가 "Invalid Date" 플레이스
 *   홀더 대신 *무언가*를 보도록.
 * - 브라우저/서버 로케일의 기본 짧은 날짜+시간 포맷팅 사용 — 운영자가
 *   "5분 전 업로드가 나타났나?"를 스캔하기에 충분한 해상도.
 */
function formatUploadedAt(value: string | null | undefined): string {
  if (!value) return "—";
  const ts = Date.parse(value);
  if (!Number.isFinite(ts)) return value;
  const d = new Date(ts);
  return d.toLocaleString();
}

export default VideosListTable;
