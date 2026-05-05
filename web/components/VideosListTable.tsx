/**
 * 업로드된 영상 목록.
 *
 * 데스크탑(>=md)은 테이블, 모바일(<md)은 카드 리스트로 자동 전환.
 * 광고주(B2B 고객) 대상이라 내부 식별자(저장 파일명, 영상 UUID) 는 노출하지
 * 않음 — 원본 파일명·크기·업로드 시각 같은 광고주가 신경 쓸 정보만 표시.
 */

import Link from "next/link";

import { apiUrl } from "@/lib/api";
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
      {/* 데스크탑 */}
      <div className="hidden md:block w-full overflow-x-auto rounded-lg border border-border bg-card">
        <Table aria-label="업로드된 영상 목록">
          <TableHeader>
            <TableRow>
              <TableHead className="min-w-[260px]">파일명</TableHead>
              <TableHead className="w-[100px]">크기</TableHead>
              <TableHead className="w-[180px]">업로드</TableHead>
              <TableHead className="w-[90px]">미리보기</TableHead>
              <TableHead className="w-[150px] text-right">액션</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {videos.map((video) => (
              <TableRow key={video.id || video.filename}>
                <TableCell className="font-semibold">
                  {video.originalName || "이름 없음"}
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
            <div className="font-semibold">
              {video.originalName || "이름 없음"}
            </div>
            <div className="mt-1 flex flex-wrap gap-x-3 gap-y-1 text-xs text-muted-foreground">
              <span>{formatBytes(video.sizeBytes)}</span>
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
