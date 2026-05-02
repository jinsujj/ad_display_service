"use client";

/**
 * Video upload form (AC 40104, Sub-AC 3).
 *
 * Goal:
 *   "Build Next.js admin upload page with file input form, MP4 client-side
 *    validation, and multipart POST request to backend upload API with
 *    progress indication."
 *
 * What this component does:
 *
 *   1. Renders a file input restricted to `video/mp4,.mp4` (the OS file
 *      picker filter is hint-only — the server is the source of truth, and
 *      [validateMp4File] runs the same checks before we hit the network).
 *
 *   2. On file selection:
 *        - shows the picked file's name + size + MIME type;
 *        - runs synchronous MP4 / size validation via [validateMp4File] so
 *          the operator gets an immediate "wrong type" / "too large" / "0 B"
 *          error without waiting for an upload round-trip.
 *
 *   3. On submit:
 *        - calls [uploadVideo] (which uses XMLHttpRequest to expose the
 *          per-tick upload progress that `fetch()` does not);
 *        - renders a determinate <progress> bar plus a "X% — N MiB of M MiB"
 *          textual indicator;
 *        - allows the operator to cancel an in-flight upload (AbortSignal);
 *        - on success: shows the persisted video's id, server filename,
 *          uploaded size, and a "Play in browser" link to the streaming URL.
 *          Clears the form so the operator can upload another in sequence.
 *        - on failure: surfaces the backend's `ApiError.message` (so a 415
 *          says "Unsupported video MIME type", a 413 says "Uploaded file
 *          exceeds the maximum allowed size", etc.) and keeps the file
 *          selected so the operator can retry without re-picking.
 *
 * Why a Client Component:
 *   File input, drag-and-drop, validation, progress, and abort all need
 *   browser APIs (`File`, `FormData`, `XMLHttpRequest`, `AbortController`).
 *   The wrapper page (app/videos/page.tsx) stays a Server Component so the
 *   nav, page title, and shell render with no JS.
 */

import { useCallback, useMemo, useRef, useState } from "react";

import { apiUrl } from "@/lib/api";
import {
  MAX_UPLOAD_SIZE_BYTES,
  VideoUploadError,
  formatBytes,
  uploadVideo,
  validateMp4File,
  type VideoUploadProgress,
  type VideoUploadResponse,
} from "@/lib/videos";

/* ------------------------------------------------------ state types */

type UploadState =
  | { kind: "idle" }
  | { kind: "validating"; message: string }
  | { kind: "ready"; file: File }
  | { kind: "uploading"; file: File; progress: VideoUploadProgress }
  | { kind: "succeeded"; file: File; result: VideoUploadResponse }
  | { kind: "failed"; file: File | null; message: string; status: number | null };

/* ------------------------------------------------------ component */

export function VideoUploadForm() {
  const [state, setState] = useState<UploadState>({ kind: "idle" });
  const fileInputRef = useRef<HTMLInputElement | null>(null);
  const abortRef = useRef<AbortController | null>(null);

  /* -------- file pick handler */

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
        // Drop the picked file from the input so the same file can be re-picked
        // after the user fixes whatever was wrong (e.g. picks a smaller MP4).
        if (fileInputRef.current) fileInputRef.current.value = "";
        return;
      }
      setState({ kind: "ready", file });
    },
    [],
  );

  /* -------- submit handler */

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
            // Ignore late ticks if the user already cancelled / failed elsewhere.
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
            message: "Unknown upload failure",
            status: null,
          });
        }
      } finally {
        abortRef.current = null;
      }
    },
    [state],
  );

  /* -------- cancel / reset handlers */

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

  /* -------- derived UI bits */

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
      return `Uploading… ${formatBytes(loaded)} sent`;
    }
    const pct = Math.round((loaded / total) * 100);
    return `${pct}% — ${formatBytes(loaded)} of ${formatBytes(total)}`;
  }, [state]);

  /* -------- render */

  return (
    <form className="upload-form" onSubmit={handleSubmit} noValidate>
      <fieldset className="upload-form__fieldset" disabled={uploading}>
        <legend className="upload-form__legend">Upload an MP4</legend>

        <label htmlFor="video-file" className="upload-form__label">
          Video file
          <span className="muted upload-form__hint">
            {" "}
            · MP4 only · max {formatBytes(MAX_UPLOAD_SIZE_BYTES)}
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
              aria-label="Upload progress"
            />
            <div className="upload-form__progress-label">
              {progressLabel}
            </div>
          </div>
        )}

        {state.kind === "failed" && (
          <div className="notice notice-error" role="alert">
            <strong>Upload failed.</strong>{" "}
            {state.status ? `[HTTP ${state.status}] ` : ""}
            {state.message}
            {state.status === 401 && (
              <div className="muted" style={{ marginTop: 6 }}>
                The upload endpoint requires authentication. Sign in (or store
                a JWT under <code>localStorage.adsignage_auth_token</code>)
                and retry.
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
            <strong>Upload complete.</strong> Stored as{" "}
            <code>{state.result.filename}</code> ({formatBytes(state.result.sizeBytes)}).
            <div style={{ marginTop: 6 }}>
              <a
                href={apiUrl(state.result.url)}
                target="_blank"
                rel="noopener noreferrer"
              >
                Play in browser ↗
              </a>
              {"  ·  "}
              <span className="muted">
                id <code>{state.result.id}</code>
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
            {uploading ? "Uploading…" : "Upload video"}
          </button>
          {uploading && (
            <button type="button" className="btn" onClick={handleCancel}>
              Cancel
            </button>
          )}
          {!uploading && (state.kind === "succeeded" || state.kind === "failed") && (
            <button type="button" className="btn" onClick={handleReset}>
              {succeeded ? "Upload another" : "Reset"}
            </button>
          )}
        </div>
      </fieldset>
    </form>
  );
}

/* ------------------------------------------------------ subcomponents */

function FilePreview({ file }: { file: File }) {
  return (
    <div className="upload-form__preview">
      <div>
        <strong>{file.name}</strong>
      </div>
      <div className="muted upload-form__preview-meta">
        {formatBytes(file.size)}
        {file.type ? ` · ${file.type}` : " · (no MIME reported by browser)"}
      </div>
    </div>
  );
}

export default VideoUploadForm;
