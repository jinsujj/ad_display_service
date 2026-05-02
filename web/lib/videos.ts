/**
 * Videos API surface used by the admin web.
 *
 * Wire contract (Spring Boot backend, owned by AC 40104 sibling sub-ACs):
 *
 *   POST /api/videos                              (multipart/form-data)
 *     part `file`: the MP4 binary
 *   -> 201 Created, application/json
 *      {
 *        "id":           string  (UUID),
 *        "filename":     string  (server-generated on-disk name),
 *        "originalName": string  (advertiser-supplied filename),
 *        "mimeType":     string  ("video/mp4"),
 *        "sizeBytes":    number,
 *        "url":          string  ("/api/videos/{filename}"),
 *        "uploadedAt":   string  (ISO-8601 instant)
 *      }
 *
 *   Errors are mapped by GlobalExceptionHandler:
 *     400 Bad Request          empty body / missing filename / missing part /
 *                              malformed multipart
 *     413 Payload Too Large    application-level cap (500 MiB) OR servlet cap
 *     415 Unsupported Media    Content-Type missing or not in the MP4 whitelist
 *     401 Unauthorized         no/invalid JWT (the endpoint is auth-gated)
 *
 * Why XMLHttpRequest instead of `fetch`:
 *   The standard `fetch()` API on browsers does not expose a progress event for
 *   the *upload* phase (only the download phase via `Response.body`). Sub-AC 3
 *   explicitly requires "progress indication", which for a 100-MB MP4 over a
 *   restaurant Wi-Fi link is the difference between "the page is hung" and
 *   "70% — keep waiting". XMLHttpRequest's `xhr.upload.onprogress` is the only
 *   first-party browser primitive that gives us bytes-uploaded / bytes-total
 *   ticks during the upload, so the upload helper here intentionally bypasses
 *   `apiFetch` (lib/api.ts) and goes direct to XHR.
 */

import { apiFetch, apiUrl } from "./api";

/* --------------------------------------------------------------- types */

/** Wire shape returned by `POST /api/videos`. */
export interface VideoUploadResponse {
  id: string;
  filename: string;
  originalName: string;
  mimeType: string;
  sizeBytes: number;
  url: string;
  uploadedAt: string;
}

/** Backend `ApiError` JSON envelope (mirrors GlobalExceptionHandler.ApiError). */
interface ApiErrorBody {
  timestamp?: string;
  status?: number;
  error?: string;
  message?: string;
  fieldErrors?: Record<string, string> | null;
}

/**
 * Upload-specific error so callers can branch on status code without parsing
 * the message string. Mirrors the shape of `ApiError` in lib/api.ts but is
 * raised from XHR (not fetch) so we can't reuse that class directly without
 * importing it in a misleading way.
 */
export class VideoUploadError extends Error {
  readonly status: number;
  readonly body: ApiErrorBody | string | null;

  constructor(message: string, status: number, body: ApiErrorBody | string | null) {
    super(message);
    this.name = "VideoUploadError";
    this.status = status;
    this.body = body;
  }
}

/** Per-tick payload delivered to the `onProgress` callback during upload. */
export interface VideoUploadProgress {
  /** Bytes the browser has pushed to the network so far. */
  loaded: number;
  /** Total bytes to push (== file size). 0 if the browser cannot compute it. */
  total: number;
  /** True once the browser has progress totals (some early ticks may not). */
  lengthComputable: boolean;
  /**
   * Convenience derived value in [0, 1]. NaN if `total === 0` and we'd divide
   * by zero — callers should guard with `Number.isFinite(percent)` before
   * rendering.
   */
  percent: number;
}

/** Options for [uploadVideo]. */
export interface UploadVideoOptions {
  /** Called on every `xhr.upload.onprogress` tick. */
  onProgress?: (p: VideoUploadProgress) => void;
  /**
   * AbortSignal to cancel the in-flight request. Aborting rejects the returned
   * promise with a [VideoUploadError] of status `0` and message "aborted".
   */
  signal?: AbortSignal;
  /**
   * Optional bearer token (JWT) to send as `Authorization: Bearer <token>`.
   * If omitted, falls back to `localStorage.adsignage_auth_token` so a future
   * login UI just has to populate that key and uploads start authenticating.
   */
  bearerToken?: string;
}

/* ----------------------------------------------------- client-side rules */

/**
 * Hard cap mirrored from the backend's
 * `VideoStorageProperties.maxUploadSizeBytes` default (500 MiB). Re-declared
 * here so the client can reject oversize files *before* paying the upload
 * latency cost. If the server caps change, bump this constant — the server
 * will still enforce its own limit.
 */
export const MAX_UPLOAD_SIZE_BYTES: number = 500 * 1024 * 1024;

/**
 * MIME types the backend accepts (mirrors `VideoStorageProperties.allowedMimeTypes`).
 * The backend is the source of truth; this is a UX-only fast-fail.
 */
export const ALLOWED_MIME_TYPES: readonly string[] = ["video/mp4"];

/** Allowed file-extension fallback (some browsers don't set Content-Type). */
const ALLOWED_EXTENSIONS: readonly string[] = [".mp4"];

/** Result of [validateMp4File] — a discriminated union for clean call sites. */
export type Mp4ValidationResult =
  | { ok: true }
  | { ok: false; code: "empty" | "too-large" | "wrong-type"; message: string };

/**
 * Client-side MP4 / size validation. Runs synchronously against the [File]
 * metadata the browser already has — no I/O, no parsing. The backend re-runs
 * the same checks (and signs off on the bytes), so this is purely a UX layer
 * that fails fast before paying the upload cost.
 *
 * Failure codes match the three backend rejection paths:
 *   - "empty"       → backend EmptyVideoUploadException → 400
 *   - "too-large"   → backend VideoTooLargeException    → 413
 *   - "wrong-type"  → backend InvalidVideoMimeTypeException → 415
 */
export function validateMp4File(file: File | null | undefined): Mp4ValidationResult {
  if (!file) {
    return { ok: false, code: "empty", message: "No file selected." };
  }
  if (file.size === 0) {
    return { ok: false, code: "empty", message: "Selected file is empty (0 bytes)." };
  }
  if (file.size > MAX_UPLOAD_SIZE_BYTES) {
    return {
      ok: false,
      code: "too-large",
      message:
        `File is ${formatBytes(file.size)}, which exceeds the ` +
        `${formatBytes(MAX_UPLOAD_SIZE_BYTES)} upload limit.`,
    };
  }

  // Most browsers populate `file.type` from the OS MIME table when the user
  // picks a file via <input type="file">; some (notably older Edge / certain
  // Android WebViews) leave it empty and we have to fall back to the extension.
  const mime = (file.type || "").trim().toLowerCase();
  const lowerName = file.name.toLowerCase();
  const extOk = ALLOWED_EXTENSIONS.some((ext) => lowerName.endsWith(ext));

  if (mime) {
    if (!ALLOWED_MIME_TYPES.includes(mime)) {
      return {
        ok: false,
        code: "wrong-type",
        message:
          `File type "${file.type}" is not supported. ` +
          `Only MP4 (video/mp4) is accepted.`,
      };
    }
  } else if (!extOk) {
    // No type metadata AND no .mp4 extension — almost certainly not an MP4.
    return {
      ok: false,
      code: "wrong-type",
      message:
        `File "${file.name}" doesn't look like an MP4 video. ` +
        `Pick a .mp4 file.`,
    };
  }

  return { ok: true };
}

/** Pretty-print a byte count using binary IEC suffixes. */
export function formatBytes(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes < 0) return "—";
  if (bytes < 1024) return `${bytes} B`;
  const units = ["KiB", "MiB", "GiB", "TiB"];
  let value = bytes / 1024;
  let unit = units[0];
  for (let i = 1; i < units.length && value >= 1024; i++) {
    value /= 1024;
    unit = units[i];
  }
  return `${value.toFixed(value >= 100 ? 0 : value >= 10 ? 1 : 2)} ${unit}`;
}

/* ------------------------------------------------------ upload */

/** Read a JWT from localStorage (browser-only). Returns `null` server-side. */
function readStoredAuthToken(): string | null {
  if (typeof window === "undefined") return null;
  try {
    return window.localStorage.getItem("adsignage_auth_token");
  } catch {
    // Storage may throw in private mode — treat as unauthenticated.
    return null;
  }
}

/**
 * Uploads a single MP4 to `POST /api/videos` with progress reporting.
 *
 * Resolves with the parsed [VideoUploadResponse] on 2xx. Rejects with a
 * [VideoUploadError] on:
 *   - HTTP non-2xx (status carries the backend's code, body carries the
 *     parsed `ApiError` JSON or raw text fallback);
 *   - Network failure / DNS error (status 0, body null);
 *   - User abort via `options.signal` (status 0, message "aborted");
 *   - Browser-side JSON parse failure on 2xx (status 200, body == raw text).
 *
 * The function intentionally does NOT re-run client-side validation — call
 * [validateMp4File] in the form first so the user sees a synchronous error
 * before the request kicks off.
 */
export function uploadVideo(
  file: File,
  options: UploadVideoOptions = {},
): Promise<VideoUploadResponse> {
  const url = apiUrl("/api/videos");
  const { onProgress, signal, bearerToken } = options;

  return new Promise<VideoUploadResponse>((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    xhr.open("POST", url, true);
    xhr.responseType = "text"; // we'll JSON.parse manually for better errors

    // Accept JSON so a misconfigured server doesn't try to give us text/html.
    xhr.setRequestHeader("Accept", "application/json");

    // NB: we deliberately do NOT set Content-Type — the browser must set it
    // to `multipart/form-data; boundary=...` from the FormData object, and
    // setting it manually would strip the boundary and the server would
    // reject the request as malformed multipart.

    const token = bearerToken ?? readStoredAuthToken();
    if (token) {
      xhr.setRequestHeader("Authorization", `Bearer ${token}`);
    }

    if (onProgress) {
      xhr.upload.onprogress = (event: ProgressEvent) => {
        const total = event.lengthComputable ? event.total : file.size;
        const percent = total > 0 ? event.loaded / total : NaN;
        onProgress({
          loaded: event.loaded,
          total,
          lengthComputable: event.lengthComputable,
          percent,
        });
      };
    }

    // Fire a final "100%" tick on successful upload so the UI lands at 100
    // even if the server is slow to write the response back.
    xhr.upload.onload = () => {
      if (onProgress) {
        onProgress({
          loaded: file.size,
          total: file.size,
          lengthComputable: true,
          percent: 1,
        });
      }
    };

    xhr.onload = () => {
      const status = xhr.status;
      const text = xhr.responseText ?? "";
      let parsed: unknown = null;
      if (text) {
        try {
          parsed = JSON.parse(text);
        } catch {
          parsed = text; // surface raw text so callers can debug
        }
      }

      if (status >= 200 && status < 300) {
        if (parsed && typeof parsed === "object") {
          resolve(parsed as VideoUploadResponse);
        } else {
          reject(
            new VideoUploadError(
              "Server returned a non-JSON success response",
              status,
              parsed as string | null,
            ),
          );
        }
        return;
      }

      reject(
        new VideoUploadError(
          buildErrorMessage(status, parsed),
          status,
          parsed as ApiErrorBody | string | null,
        ),
      );
    };

    xhr.onerror = () => {
      reject(
        new VideoUploadError(
          "Network error — could not reach the upload endpoint.",
          0,
          null,
        ),
      );
    };

    xhr.ontimeout = () => {
      reject(new VideoUploadError("Upload timed out.", 0, null));
    };

    xhr.onabort = () => {
      reject(new VideoUploadError("Upload aborted.", 0, null));
    };

    if (signal) {
      if (signal.aborted) {
        // Abort before send — short-circuit synchronously.
        reject(new VideoUploadError("Upload aborted.", 0, null));
        return;
      }
      signal.addEventListener("abort", () => {
        try {
          xhr.abort();
        } catch {
          /* no-op */
        }
      });
    }

    const form = new FormData();
    // The server expects the multipart name `file` (see VideoController.upload).
    form.append("file", file, file.name);

    xhr.send(form);
  });
}

/* ------------------------------------------------------ list */

/**
 * Wire shape returned by `GET /api/videos`. Identical to
 * [VideoUploadResponse] so the admin UI can reuse one row renderer for both
 * the just-uploaded asset (returned by `POST /api/videos`) and the historical
 * roster fetched on page load.
 */
export type VideoListItem = VideoUploadResponse;

/**
 * Tolerant variant for legacy / alternative backend shapes — mirrors the
 * pattern used in `lib/devices.ts`. The JPA entity surface and the wire DTO
 * agree today, but accepting a couple of common alias spellings (e.g. a
 * Spring controller that wraps the collection in `{ items: [...] }`, or
 * advertisers' future `originalFilename` rename) decouples the UI from the
 * exact backend evolution path.
 */
type RawVideo = Partial<VideoListItem> & {
  videoId?: string;
  serverFilename?: string;
  originalFilename?: string;
  contentType?: string;
  size?: number;
  bytes?: number;
  streamUrl?: string;
  streamingUrl?: string;
  createdAt?: string;
};

/**
 * Normalise whatever the backend returns into the canonical [VideoListItem]
 * shape so the admin UI doesn't have to second-guess field naming.
 */
function normaliseVideo(raw: RawVideo): VideoListItem {
  const id = raw.id ?? raw.videoId ?? "";
  const filename = raw.filename ?? raw.serverFilename ?? "";
  const originalName =
    raw.originalName ?? raw.originalFilename ?? filename ?? "";
  const mimeType = raw.mimeType ?? raw.contentType ?? "";
  const sizeBytes = raw.sizeBytes ?? raw.size ?? raw.bytes ?? 0;
  const url =
    raw.url ??
    raw.streamUrl ??
    raw.streamingUrl ??
    (filename ? `/api/videos/${filename}` : "");
  const uploadedAt = raw.uploadedAt ?? raw.createdAt ?? "";

  return {
    id,
    filename,
    originalName,
    mimeType,
    sizeBytes,
    url,
    uploadedAt,
  };
}

/**
 * Fetches the full uploaded-videos list from the backend.
 *
 * Returns a normalised, newest-first [VideoListItem] array. The backend is
 * expected to sort `uploaded_at DESC` (see
 * `VideoRepository.findAllByOrderByUploadedAtDesc`), but the UI re-sorts as
 * a defensive belt-and-braces — if a future caching layer or a tolerant
 * shim returns rows in an unexpected order, the admin still sees the most
 * recent upload at the top.
 *
 * Throws [ApiError] from `lib/api.ts` if the backend responds with non-2xx
 * — callers (e.g. the videos page) catch this and render an inline error
 * notice rather than letting the page crash.
 */
export async function listVideos(): Promise<VideoListItem[]> {
  const raw = await apiFetch<RawVideo[] | { items?: RawVideo[] }>(
    "/api/videos",
  );

  // Some Spring controllers wrap collections in `{ items: [...] }`. Accept both.
  const items = Array.isArray(raw)
    ? raw
    : Array.isArray(raw?.items)
      ? raw.items
      : [];

  const normalised = items.map(normaliseVideo);

  // Defensive newest-first sort. Falsy / unparseable timestamps end up at the
  // bottom (Date.parse → NaN, which is treated as -Infinity below).
  return normalised.slice().sort((a, b) => {
    const ta = Date.parse(a.uploadedAt);
    const tb = Date.parse(b.uploadedAt);
    const va = Number.isFinite(ta) ? ta : -Infinity;
    const vb = Number.isFinite(tb) ? tb : -Infinity;
    return vb - va;
  });
}

/** Build a human-friendly error message from the parsed API error body. */
function buildErrorMessage(status: number, body: unknown): string {
  if (body && typeof body === "object") {
    const apiError = body as ApiErrorBody;
    const msg = apiError.message?.trim();
    if (msg) {
      return `${status} ${apiError.error ?? ""}`.trim() + ` — ${msg}`;
    }
  }
  if (typeof body === "string" && body.trim()) {
    return `HTTP ${status} — ${body.slice(0, 200)}`;
  }
  return `Upload failed with HTTP ${status}.`;
}
