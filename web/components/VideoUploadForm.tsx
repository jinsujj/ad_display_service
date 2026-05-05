"use client";

/**
 * 영상 업로드 폼 (AC 40104, Sub-AC 3).
 *
 * MP4 전용, 클라이언트 측 검증, multipart POST + 진행률(XHR), 취소(AbortController).
 * 폼 검증/업로드 로직은 그대로, shadcn 프리미티브로 갈음.
 */

import { useCallback, useMemo, useRef, useState } from "react";

import { apiUrl } from "@/lib/api";
import { shortId } from "@/lib/format";
import {
  MAX_UPLOAD_SIZE_BYTES,
  VideoUploadError,
  formatBytes,
  uploadVideo,
  validateMp4File,
  type VideoUploadProgress,
  type VideoUploadResponse,
} from "@/lib/videos";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Label } from "@/components/ui/label";
import { Progress } from "@/components/ui/progress";

type UploadState =
  | { kind: "idle" }
  | { kind: "validating"; message: string }
  | { kind: "ready"; file: File }
  | { kind: "uploading"; file: File; progress: VideoUploadProgress }
  | { kind: "succeeded"; file: File; result: VideoUploadResponse }
  | { kind: "failed"; file: File | null; message: string; status: number | null };

export function VideoUploadForm() {
  const [state, setState] = useState<UploadState>({ kind: "idle" });
  const fileInputRef = useRef<HTMLInputElement | null>(null);
  const abortRef = useRef<AbortController | null>(null);

  const handleFileChange = useCallback(
    (event: React.ChangeEvent<HTMLInputElement>) => {
      const file = event.target.files?.[0] ?? null;
      if (!file) {
        setState({ kind: "idle" });
        return;
      }
      const result = validateMp4File(file);
      if (!result.ok) {
        setState({
          kind: "failed",
          file: null,
          message: result.message,
          status: null,
        });
        if (fileInputRef.current) fileInputRef.current.value = "";
        return;
      }
      setState({ kind: "ready", file });
    },
    [],
  );

  const handleSubmit = useCallback(
    async (event: React.FormEvent<HTMLFormElement>) => {
      event.preventDefault();
      const current = state;
      if (current.kind !== "ready") return;

      const controller = new AbortController();
      abortRef.current = controller;

      setState({
        kind: "uploading",
        file: current.file,
        progress: {
          loaded: 0,
          total: current.file.size,
          lengthComputable: true,
          percent: 0,
        },
      });

      try {
        const result = await uploadVideo(current.file, {
          signal: controller.signal,
          onProgress: (p) => {
            setState((s) =>
              s.kind === "uploading" && s.file === current.file
                ? { ...s, progress: p }
                : s,
            );
          },
        });
        setState({ kind: "succeeded", file: current.file, result });
        if (fileInputRef.current) fileInputRef.current.value = "";
      } catch (err) {
        if (err instanceof VideoUploadError) {
          setState({
            kind: "failed",
            file: current.file,
            message: err.message,
            status: err.status === 0 ? null : err.status,
          });
        } else if (err instanceof Error) {
          setState({
            kind: "failed",
            file: current.file,
            message: err.message,
            status: null,
          });
        } else {
          setState({
            kind: "failed",
            file: current.file,
            message: "알 수 없는 업로드 오류",
            status: null,
          });
        }
      } finally {
        abortRef.current = null;
      }
    },
    [state],
  );

  const handleCancel = useCallback(() => {
    abortRef.current?.abort();
    abortRef.current = null;
  }, []);

  const handleReset = useCallback(() => {
    abortRef.current?.abort();
    abortRef.current = null;
    if (fileInputRef.current) fileInputRef.current.value = "";
    setState({ kind: "idle" });
  }, []);

  const uploading = state.kind === "uploading";
  const succeeded = state.kind === "succeeded";
  const submitDisabled = state.kind !== "ready";

  const progressPercent =
    state.kind === "uploading" && Number.isFinite(state.progress.percent)
      ? Math.round(Math.min(1, Math.max(0, state.progress.percent)) * 100)
      : 0;

  const progressLabel = useMemo(() => {
    if (state.kind !== "uploading") return null;
    const { loaded, total, lengthComputable } = state.progress;
    if (!lengthComputable || !total) {
      return `업로드 중… ${formatBytes(loaded)} 전송됨`;
    }
    const pct = Math.round((loaded / total) * 100);
    return `${pct}% — ${formatBytes(loaded)} / ${formatBytes(total)}`;
  }, [state]);

  return (
    <Card>
      <CardHeader>
        <CardTitle>MP4 업로드</CardTitle>
      </CardHeader>
      <CardContent>
        <form className="space-y-4" onSubmit={handleSubmit} noValidate>
          <fieldset className="space-y-4" disabled={uploading}>
            <div className="space-y-1.5">
              <Label
                htmlFor="video-file"
                className="flex flex-wrap items-baseline gap-2"
              >
                영상 파일
                <span className="text-xs font-normal text-muted-foreground">
                  · MP4 전용 · 최대 {formatBytes(MAX_UPLOAD_SIZE_BYTES)}
                </span>
              </Label>
              <input
                ref={fileInputRef}
                id="video-file"
                name="file"
                type="file"
                accept="video/mp4,.mp4"
                onChange={handleFileChange}
                className="block w-full text-sm file:mr-3 file:h-10 file:rounded-md file:border-0 file:bg-secondary file:px-3 file:text-sm file:font-medium file:text-secondary-foreground hover:file:bg-secondary/80"
              />
            </div>

            {(state.kind === "ready" ||
              state.kind === "uploading" ||
              state.kind === "succeeded") && <FilePreview file={state.file} />}

            {state.kind === "uploading" && (
              <div
                className="space-y-1.5"
                role="status"
                aria-live="polite"
              >
                <Progress
                  value={progressPercent}
                  aria-label="업로드 진행률"
                />
                <div className="text-xs text-muted-foreground">
                  {progressLabel}
                </div>
              </div>
            )}

            {state.kind === "failed" && (
              <Alert variant="destructive">
                <AlertDescription>
                  <strong>업로드 실패.</strong>{" "}
                  {state.status ? `[HTTP ${state.status}] ` : ""}
                  {state.message}
                  {state.status === 401 && (
                    <div className="mt-1.5 text-xs opacity-90">
                      업로드 엔드포인트는 인증이 필요합니다. 먼저 로그인 후
                      다시 시도해 주세요.
                    </div>
                  )}
                </AlertDescription>
              </Alert>
            )}

            {state.kind === "succeeded" && (
              <Alert variant="ok">
                <AlertDescription>
                  <strong>업로드 완료.</strong> 저장 파일명{" "}
                  <code className="font-mono">{state.result.filename}</code> (
                  {formatBytes(state.result.sizeBytes)}).
                  <div className="mt-1.5">
                    <a
                      href={apiUrl(state.result.url)}
                      target="_blank"
                      rel="noopener noreferrer"
                      className="text-emerald-300 underline-offset-4 hover:underline"
                    >
                      브라우저에서 재생 ↗
                    </a>
                    {"  ·  "}
                    <span title={state.result.id}>
                      ID{" "}
                      <code className="font-mono">
                        {shortId(state.result.id)}
                      </code>
                    </span>
                  </div>
                </AlertDescription>
              </Alert>
            )}

            <div className="flex flex-col gap-2 sm:flex-row sm:items-center">
              <Button
                type="submit"
                disabled={submitDisabled}
                aria-busy={uploading}
                className="sm:w-auto"
              >
                {uploading ? "업로드 중…" : "영상 업로드"}
              </Button>
              {uploading && (
                <Button
                  type="button"
                  variant="outline"
                  onClick={handleCancel}
                >
                  취소
                </Button>
              )}
              {!uploading &&
                (state.kind === "succeeded" || state.kind === "failed") && (
                  <Button
                    type="button"
                    variant="outline"
                    onClick={handleReset}
                  >
                    {succeeded ? "다른 영상 업로드" : "초기화"}
                  </Button>
                )}
            </div>
          </fieldset>
        </form>
      </CardContent>
    </Card>
  );
}

function FilePreview({ file }: { file: File }) {
  return (
    <div className="rounded-md border border-border bg-background/50 p-3">
      <div className="font-semibold">{file.name}</div>
      <div className="mt-0.5 text-xs text-muted-foreground">
        {formatBytes(file.size)}
        {file.type ? ` · ${file.type}` : " · (브라우저가 MIME을 보고하지 않음)"}
      </div>
    </div>
  );
}

export default VideoUploadForm;
