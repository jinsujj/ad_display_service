"use client";

/**
 * 영상 업로드 폼 (AC 40104, Sub-AC 3).
 *
 * 목표:
 *   "Build Next.js admin upload page with file input form, MP4 client-side
 *    validation, and multipart POST request to backend upload API with
 *    progress indication."
 *
 * 이 컴포넌트가 하는 일:
 *
 *   1. `video/mp4,.mp4`로 제한된 파일 입력을 렌더링(OS 파일 피커 필터는
 *      힌트 전용 — 서버가 진실의 원천이고, [validateMp4File]가 네트워크에
 *      도달하기 전에 동일한 검사를 수행).
 *
 *   2. 파일 선택 시:
 *        - 선택된 파일의 이름 + 크기 + MIME 타입을 표시;
 *        - [validateMp4File]로 동기 MP4/크기 검증을 실행해 운영자가 업로드
 *          왕복 없이 즉시 "잘못된 타입" / "너무 큼" / "0 B" 오류를 받는다.
 *
 *   3. 제출 시:
 *        - [uploadVideo](XMLHttpRequest를 사용해 `fetch()`가 노출하지 않는
 *          틱 단위 업로드 진행률을 노출) 호출;
 *        - 결정적 <progress> 바와 "X% — N MiB of M MiB" 텍스트 표시기를
 *          렌더링;
 *        - 진행 중 업로드를 취소할 수 있게 함(AbortSignal);
 *        - 성공: 영속화된 영상의 id, 서버 파일명, 업로드된 크기, 그리고
 *          스트리밍 URL로의 "Play in browser" 링크를 표시. 운영자가 다음
 *          업로드를 진행할 수 있도록 폼을 비운다.
 *        - 실패: 백엔드의 `ApiError.message`를 노출(415는 "Unsupported
 *          video MIME type", 413은 "Uploaded file exceeds the maximum
 *          allowed size" 등)하고 파일을 그대로 두어 운영자가 다시 고를
 *          필요 없이 재시도할 수 있게 한다.
 *
 * 왜 클라이언트 컴포넌트인가:
 *   파일 입력, 드래그 앤 드롭, 검증, 진행률, 중단 모두 브라우저 API
 *   (`File`, `FormData`, `XMLHttpRequest`, `AbortController`)가 필요하다.
 *   래퍼 페이지(app/videos/page.tsx)는 서버 컴포넌트로 유지되어 내비게이션,
 *   페이지 제목, 셸이 JS 없이 렌더링된다.
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

/* ------------------------------------------------------ 상태 타입 */

type UploadState =
  | { kind: "idle" }
  | { kind: "validating"; message: string }
  | { kind: "ready"; file: File }
  | { kind: "uploading"; file: File; progress: VideoUploadProgress }
  | { kind: "succeeded"; file: File; result: VideoUploadResponse }
  | { kind: "failed"; file: File | null; message: string; status: number | null };

/* ------------------------------------------------------ 컴포넌트 */

export function VideoUploadForm() {
  const [state, setState] = useState<UploadState>({ kind: "idle" });
  const fileInputRef = useRef<HTMLInputElement | null>(null);
  const abortRef = useRef<AbortController | null>(null);

  /* -------- 파일 선택 핸들러 */

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
        // 사용자가 잘못된 점을 고치면(예: 더 작은 MP4 선택) 같은 파일을
        // 다시 고를 수 있도록 입력에서 선택된 파일을 제거.
        if (fileInputRef.current) fileInputRef.current.value = "";
        return;
      }
      setState({ kind: "ready", file });
    },
    [],
  );

  /* -------- 제출 핸들러 */

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
            // 사용자가 이미 다른 곳에서 취소/실패한 경우 늦은 틱 무시.
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

  /* -------- 취소/리셋 핸들러 */

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

  /* -------- 파생 UI 비트 */

  const uploading = state.kind === "uploading";
  const succeeded = state.kind === "succeeded";

  const submitDisabled = state.kind !== "ready";

  const progressPercent =
    state.kind === "uploading" && Number.isFinite(state.progress.percent)
      ? Math.min(1, Math.max(0, state.progress.percent))
      : null;

  const progressLabel = useMemo(() => {
    if (state.kind !== "uploading") return null;
    const { loaded, total, lengthComputable } = state.progress;
    if (!lengthComputable || !total) {
      return `업로드 중… ${formatBytes(loaded)} 전송됨`;
    }
    const pct = Math.round((loaded / total) * 100);
    return `${pct}% — ${formatBytes(loaded)} / ${formatBytes(total)}`;
  }, [state]);

  /* -------- 렌더 */

  return (
    <form className="upload-form" onSubmit={handleSubmit} noValidate>
      <fieldset className="upload-form__fieldset" disabled={uploading}>
        <legend className="upload-form__legend">MP4 업로드</legend>

        <label htmlFor="video-file" className="upload-form__label">
          영상 파일
          <span className="muted upload-form__hint">
            {" "}
            · MP4 전용 · 최대 {formatBytes(MAX_UPLOAD_SIZE_BYTES)}
          </span>
        </label>
        <input
          ref={fileInputRef}
          id="video-file"
          name="file"
          type="file"
          accept="video/mp4,.mp4"
          className="upload-form__file"
          onChange={handleFileChange}
        />

        {state.kind === "ready" && (
          <FilePreview file={state.file} />
        )}

        {state.kind === "uploading" && (
          <FilePreview file={state.file} />
        )}

        {state.kind === "succeeded" && (
          <FilePreview file={state.file} />
        )}

        {state.kind === "uploading" && (
          <div className="upload-form__progress" role="status" aria-live="polite">
            <progress
              className="upload-form__bar"
              max={1}
              value={progressPercent ?? undefined}
              aria-label="업로드 진행률"
            />
            <div className="upload-form__progress-label">
              {progressLabel}
            </div>
          </div>
        )}

        {state.kind === "failed" && (
          <div className="notice notice-error" role="alert">
            <strong>업로드 실패.</strong>{" "}
            {state.status ? `[HTTP ${state.status}] ` : ""}
            {state.message}
            {state.status === 401 && (
              <div className="muted" style={{ marginTop: 6 }}>
                업로드 엔드포인트는 인증이 필요합니다. 먼저 로그인 후 다시
                시도해 주세요.
              </div>
            )}
          </div>
        )}

        {state.kind === "succeeded" && (
          <div
            className="notice"
            role="status"
            style={{
              borderColor: "rgba(74, 222, 128, 0.5)",
              background: "rgba(74, 222, 128, 0.08)",
              color: "var(--ok)",
            }}
          >
            <strong>업로드 완료.</strong> 저장 파일명{" "}
            <code>{state.result.filename}</code> ({formatBytes(state.result.sizeBytes)}).
            <div style={{ marginTop: 6 }}>
              <a
                href={apiUrl(state.result.url)}
                target="_blank"
                rel="noopener noreferrer"
              >
                브라우저에서 재생 ↗
              </a>
              {"  ·  "}
              <span className="muted" title={state.result.id}>
                ID <code>{shortId(state.result.id)}</code>
              </span>
            </div>
          </div>
        )}

        <div className="toolbar upload-form__actions">
          <button
            type="submit"
            className="btn"
            disabled={submitDisabled}
            aria-busy={uploading}
          >
            {uploading ? "업로드 중…" : "영상 업로드"}
          </button>
          {uploading && (
            <button type="button" className="btn" onClick={handleCancel}>
              취소
            </button>
          )}
          {!uploading && (state.kind === "succeeded" || state.kind === "failed") && (
            <button type="button" className="btn" onClick={handleReset}>
              {succeeded ? "다른 영상 업로드" : "초기화"}
            </button>
          )}
        </div>
      </fieldset>
    </form>
  );
}

/* ------------------------------------------------------ 하위 컴포넌트 */

function FilePreview({ file }: { file: File }) {
  return (
    <div className="upload-form__preview">
      <div>
        <strong>{file.name}</strong>
      </div>
      <div className="muted upload-form__preview-meta">
        {formatBytes(file.size)}
        {file.type ? ` · ${file.type}` : " · (브라우저가 MIME을 보고하지 않음)"}
      </div>
    </div>
  );
}

export default VideoUploadForm;
