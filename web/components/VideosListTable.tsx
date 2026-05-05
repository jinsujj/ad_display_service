/**
 * 업로드된 영상 목록.
 *
 * 데스크탑(>=md)은 7컬럼 테이블, 모바일(<md)은 카드 리스트로 자동 전환.
 * 서버 컴포넌트 — 부모(서버) 가 GET /api/videos 를 fetch 해 행을 넘긴다.
 */

import Link from "next/link";

import { apiUrl } from "@/lib/api";
import { shortId } from "@/lib/format";
import { formatBytes, type VideoListItem } from "@/lib/videos";
import { Button } from "@/components/ui/button";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";

interface VideosListTableProps {
  videos: VideoListItem[];
}

export function VideosListTable({ videos }: VideosListTableProps) {
  return (
    <>
      {/* 데스크탑 — 7컬럼 테이블 */}
      <div className="hidden md:block w-full overflow-x-auto rounded-lg border border-border bg-card">
        <Table aria-label="업로드된 영상 목록">
          <TableHeader>
            <TableRow>
              <TableHead className="min-w-[200px]">원본 파일명</TableHead>
              <TableHead className="w-[220px]">저장 파일명</TableHead>
              <TableHead className="w-[92px]">타입</TableHead>
              <TableHead className="w-[92px]">크기</TableHead>
              <TableHead className="w-[160px]">업로드</TableHead>
              <TableHead className="w-[80px]">미리보기</TableHead>
              <TableHead className="w-[140px] text-right">액션</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {videos.map((video) => (
              <TableRow key={video.id || video.filename}>
                <TableCell>
                  <div className="font-semibold">
                    {video.originalName || "—"}
                  </div>
                  {video.id && (
                    <div
                      className="mt-1 truncate text-[11px] text-muted-foreground"
                      title={video.id}
                    >
                      ID <code className="font-mono">{shortId(video.id)}</code>
                    </div>
                  )}
                </TableCell>
                <TableCell
                  className="font-mono text-xs"
                  title={video.filename || ""}
                >
                  {video.filename || "—"}
                </TableCell>
                <TableCell className="text-xs text-muted-foreground">
                  {video.mimeType || "—"}
                </TableCell>
                <TableCell>{formatBytes(video.sizeBytes)}</TableCell>
                <TableCell className="text-xs text-muted-foreground">
                  {formatUploadedAt(video.uploadedAt)}
                </TableCell>
                <TableCell>
                  {video.url ? (
                    <a
                      href={apiUrl(video.url)}
                      target="_blank"
                      rel="noopener noreferrer"
                      className="text-accent underline-offset-4 hover:underline"
                    >
                      재생 ↗
                    </a>
                  ) : (
                    <span className="text-muted-foreground">—</span>
                  )}
                </TableCell>
                <TableCell className="text-right">
                  {video.filename ? (
                    <Button asChild variant="outline" size="sm">
                      <Link
                        href={{
                          pathname: "/ads/new",
                          query: {
                            videoFilename: video.filename,
                            originalName: video.originalName ?? "",
                            title:
                              video.originalName?.replace(/\.[^.]+$/, "") ??
                              "",
                          },
                        }}
                      >
                        광고 만들기
                      </Link>
                    </Button>
                  ) : (
                    <span className="text-muted-foreground">—</span>
                  )}
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </div>

      {/* 모바일 — 카드 리스트 */}
      <ul
        className="md:hidden flex flex-col gap-2.5"
        aria-label="업로드된 영상 (모바일 보기)"
      >
        {videos.map((video) => (
          <li
            key={video.id || video.filename}
            className="rounded-lg border border-border bg-card p-3"
          >
            <div className="font-semibold">{video.originalName || "—"}</div>
            {video.filename && (
              <div
                className="mt-0.5 truncate font-mono text-xs text-muted-foreground"
                title={video.filename}
              >
                {video.filename}
              </div>
            )}
            <div className="mt-2 flex flex-wrap gap-x-3 gap-y-1 text-xs text-muted-foreground">
              {video.id && (
                <span title={video.id}>
                  ID <code className="font-mono">{shortId(video.id)}</code>
                </span>
              )}
              <span>{formatBytes(video.sizeBytes)}</span>
              {video.mimeType && <span>{video.mimeType}</span>}
              <span>{formatUploadedAt(video.uploadedAt)}</span>
            </div>
            <div className="mt-3 flex gap-2">
              {video.url ? (
                <Button asChild variant="outline" className="flex-1">
                  <a
                    href={apiUrl(video.url)}
                    target="_blank"
                    rel="noopener noreferrer"
                  >
                    재생 ↗
                  </a>
                </Button>
              ) : (
                <Button variant="outline" className="flex-1" disabled>
                  재생 불가
                </Button>
              )}
              {video.filename && (
                <Button asChild className="flex-1">
                  <Link
                    href={{
                      pathname: "/ads/new",
                      query: {
                        videoFilename: video.filename,
                        originalName: video.originalName ?? "",
                        title:
                          video.originalName?.replace(/\.[^.]+$/, "") ?? "",
                      },
                    }}
                  >
                    광고 만들기
                  </Link>
                </Button>
              )}
            </div>
          </li>
        ))}
      </ul>
    </>
  );
}

function formatUploadedAt(value: string | null | undefined): string {
  if (!value) return "—";
  const ts = Date.parse(value);
  if (!Number.isFinite(ts)) return value;
  const d = new Date(ts);
  return d.toLocaleString();
}

export default VideosListTable;
