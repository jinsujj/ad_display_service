/**
 * 관리자 웹이 사용하는 비디오 API 표면.
 *
 * 와이어 계약(Spring Boot 백엔드, AC 40104 형제 Sub-AC 소관):
 *
 *   POST /api/videos                              (multipart/form-data)
 *     part `file`: MP4 바이너리
 *   -> 201 Created, application/json
 *      {
 *        "id":           string  (UUID),
 *        "filename":     string  (서버 생성 디스크 파일명),
 *        "originalName": string  (광고주가 제공한 파일명),
 *        "mimeType":     string  ("video/mp4"),
 *        "sizeBytes":    number,
 *        "url":          string  ("/api/videos/{filename}"),
 *        "uploadedAt":   string  (ISO-8601 instant)
 *      }
 *
 *   에러는 GlobalExceptionHandler가 매핑한다:
 *     400 Bad Request          빈 본문 / 파일명 누락 / part 누락 /
 *                              malformed multipart
 *     413 Payload Too Large    애플리케이션 레벨 한도(500 MiB) 또는 servlet 한도
 *     415 Unsupported Media    Content-Type 누락 또는 MP4 화이트리스트에 없음
 *     401 Unauthorized         JWT 없음/유효하지 않음(엔드포인트는 인증 게이트)
 *
 * `fetch` 대신 XMLHttpRequest를 사용하는 이유:
 *   브라우저의 표준 `fetch()` API는 *업로드* 단계의 progress 이벤트를 노출하지
 *   않는다(다운로드 단계만 `Response.body`로 노출). Sub-AC 3은 명시적으로
 *   "progress indication"을 요구하며, 식당 Wi-Fi에서 100MB MP4를 올릴 때
 *   "페이지가 멈춘 것 같다"와 "70% — 기다리세요"의 차이를 만든다. XMLHttpRequest의
 *   `xhr.upload.onprogress`는 업로드 중 bytes-uploaded / bytes-total 틱을
 *   제공하는 유일한 일급 브라우저 기본 기능이므로, 여기 업로드 헬퍼는 의도적으로
 *   `apiFetch`(lib/api.ts)를 우회해 XHR을 직접 사용한다.
 */

import { apiFetch, apiUrl } from "./api";

/* --------------------------------------------------------------- types */

/** `POST /api/videos`가 반환하는 와이어 형태. */
export interface VideoUploadResponse {
  id: string;
  filename: string;
  originalName: string;
  mimeType: string;
  sizeBytes: number;
  url: string;
  uploadedAt: string;
}

/** 백엔드 `ApiError` JSON 봉투(GlobalExceptionHandler.ApiError와 동일). */
interface ApiErrorBody {
  timestamp?: string;
  status?: number;
  error?: string;
  message?: string;
  fieldErrors?: Record<string, string> | null;
}

/**
 * 호출자가 메시지 문자열을 파싱하지 않고도 상태 코드로 분기할 수 있도록 한
 * 업로드 전용 에러. lib/api.ts의 `ApiError` 형태를 따르지만 XHR(fetch가 아님)에서
 * 발생하므로, 그 클래스를 오해 소지 없이 직접 재사용할 수는 없다.
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

/** 업로드 중 `onProgress` 콜백에 전달되는 틱별 페이로드. */
export interface VideoUploadProgress {
  /** 지금까지 브라우저가 네트워크로 푸시한 바이트 수. */
  loaded: number;
  /** 푸시할 총 바이트 수(= 파일 크기). 브라우저가 계산할 수 없으면 0. */
  total: number;
  /** 브라우저가 진행률 총량을 알고 있으면 true(초기 일부 틱은 모를 수 있음). */
  lengthComputable: boolean;
  /**
   * [0, 1] 범위의 편의 파생값. `total === 0`이면 0으로 나누게 되어 NaN이 된다 —
   * 호출자는 렌더링 전에 `Number.isFinite(percent)`로 가드해야 한다.
   */
  percent: number;
}

/** [uploadVideo]의 옵션. */
export interface UploadVideoOptions {
  /** 모든 `xhr.upload.onprogress` 틱에서 호출된다. */
  onProgress?: (p: VideoUploadProgress) => void;
  /**
   * 진행 중 요청을 취소할 AbortSignal. abort 시 반환 Promise는 status `0`,
   * message "aborted"의 [VideoUploadError]로 reject된다.
   */
  signal?: AbortSignal;
  /**
   * `Authorization: Bearer <token>`으로 전송할 선택적 bearer 토큰(JWT).
   * 생략 시 `localStorage.adsignage_auth_token`으로 폴백하므로, 향후 로그인
   * UI는 그 키만 채우면 업로드 인증이 시작된다.
   */
  bearerToken?: string;
}

/* ----------------------------------------------------- client-side rules */

/**
 * 백엔드의 `VideoStorageProperties.maxUploadSizeBytes` 기본값(500 MiB)을
 * 그대로 반영한 하드 한도. 클라이언트가 업로드 지연 비용을 *지불하기 전에*
 * 큰 파일을 거부할 수 있도록 여기 다시 선언한다. 서버 한도가 바뀌면 이 상수를
 * 갱신한다 — 서버는 그래도 자체 한도를 강제한다.
 */
export const MAX_UPLOAD_SIZE_BYTES: number = 500 * 1024 * 1024;

/**
 * 백엔드가 허용하는 MIME 타입(`VideoStorageProperties.allowedMimeTypes`와 동일).
 * 진실의 출처는 백엔드이며, 이것은 UX 전용 빠른 실패다.
 */
export const ALLOWED_MIME_TYPES: readonly string[] = ["video/mp4"];

/** 허용 확장자 폴백(일부 브라우저는 Content-Type을 설정하지 않음). */
const ALLOWED_EXTENSIONS: readonly string[] = [".mp4"];

/** [validateMp4File]의 결과 — 깔끔한 호출 지점을 위한 식별 유니온. */
export type Mp4ValidationResult =
  | { ok: true }
  | { ok: false; code: "empty" | "too-large" | "wrong-type"; message: string };

/**
 * 클라이언트 측 MP4 / 크기 검증. 브라우저가 이미 가지고 있는 [File] 메타데이터에
 * 대해 동기적으로 실행된다 — I/O 없음, 파싱 없음. 백엔드가 동일한 검사를
 * 다시 수행하고 바이트도 검사하므로, 이것은 업로드 비용을 지불하기 전에 빠르게
 * 실패시키기 위한 순수 UX 레이어다.
 *
 * 실패 코드는 백엔드의 세 가지 거부 경로와 일치한다:
 *   - "empty"       → 백엔드 EmptyVideoUploadException → 400
 *   - "too-large"   → 백엔드 VideoTooLargeException    → 413
 *   - "wrong-type"  → 백엔드 InvalidVideoMimeTypeException → 415
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

  // 대부분의 브라우저는 사용자가 <input type="file">로 파일을 고를 때 OS MIME
  // 테이블에서 `file.type`을 채운다. 일부(특히 구형 Edge / 특정 Android
  // WebView)는 비워두므로, 확장자로 폴백해야 한다.
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
    // 타입 메타데이터도 없고 .mp4 확장자도 없음 — 거의 확실히 MP4가 아님.
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

/** 이진 IEC 접미사로 바이트 수를 보기 좋게 출력한다. */
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

/** localStorage에서 JWT를 읽는다(브라우저 전용). 서버 사이드에서는 `null` 반환. */
function readStoredAuthToken(): string | null {
  if (typeof window === "undefined") return null;
  try {
    return window.localStorage.getItem("adsignage_auth_token");
  } catch {
    // 시크릿 모드에서는 스토리지 접근이 throw할 수 있다 — 미인증으로 취급.
    return null;
  }
}

/**
 * 진행률 보고를 포함하여 단일 MP4를 `POST /api/videos`로 업로드한다.
 *
 * 2xx에서는 파싱된 [VideoUploadResponse]로 resolve한다. 다음의 경우
 * [VideoUploadError]로 reject한다:
 *   - HTTP 비-2xx(status는 백엔드 코드, body는 파싱된 `ApiError` JSON 또는
 *     원본 텍스트 폴백);
 *   - 네트워크 실패 / DNS 에러(status 0, body null);
 *   - `options.signal`을 통한 사용자 abort(status 0, message "aborted");
 *   - 2xx에서 브라우저 측 JSON 파싱 실패(status 200, body == 원본 텍스트).
 *
 * 이 함수는 클라이언트 측 검증을 의도적으로 다시 실행하지 *않는다* — 폼에서
 * 먼저 [validateMp4File]을 호출해 사용자가 요청 전에 동기 에러를 확인하도록 한다.
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
    xhr.responseType = "text"; // 더 나은 에러를 위해 JSON.parse는 직접 수행

    // 잘못 설정된 서버가 text/html을 주려고 시도하지 않도록 JSON을 Accept.
    xhr.setRequestHeader("Accept", "application/json");

    // 주의: Content-Type은 의도적으로 설정하지 *않는다* — 브라우저가 FormData
    // 객체로부터 `multipart/form-data; boundary=...`을 직접 설정해야 하며,
    // 수동으로 설정하면 boundary가 제거되어 서버가 malformed multipart로
    // 요청을 거부하게 된다.

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

    // 업로드 성공 시 마지막 "100%" 틱을 발생시켜, 서버가 응답을 늦게 써내도
    // UI는 100에 도달하도록 한다.
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
          parsed = text; // 호출자가 디버깅할 수 있도록 원본 텍스트 노출
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
        // 전송 전 abort — 동기적으로 단락 처리.
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
    // 서버는 multipart 이름으로 `file`을 기대한다(VideoController.upload 참조).
    form.append("file", file, file.name);

    xhr.send(form);
  });
}

/* ------------------------------------------------------ list */

/**
 * `GET /api/videos`가 반환하는 와이어 형태. [VideoUploadResponse]와 동일하므로,
 * 관리자 UI는 방금 업로드된 자산(`POST /api/videos` 반환)과 페이지 로드 시
 * 가져오는 기존 목록 모두에 동일한 행 렌더러를 재사용할 수 있다.
 */
export type VideoListItem = VideoUploadResponse;

/**
 * 레거시 / 대체 백엔드 형태에 관대한 변형 — `lib/devices.ts`에서 사용된
 * 패턴과 동일. JPA 엔티티 표면과 와이어 DTO는 현재 일치하지만, 흔한 별칭
 * 표기 몇 가지(예: 컬렉션을 `{ items: [...] }`로 감싸는 Spring 컨트롤러,
 * 광고주의 향후 `originalFilename` 이름 변경)를 허용함으로써 UI를 정확한
 * 백엔드 진화 경로와 결합 해제한다.
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
 * 백엔드가 반환하는 형태를 표준 [VideoListItem] 형태로 정규화하여, 관리자
 * UI가 필드 이름을 추측할 필요가 없도록 한다.
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
 * 백엔드에서 업로드된 비디오 전체 목록을 가져온다.
 *
 * 정규화되고 최신순으로 정렬된 [VideoListItem] 배열을 반환한다. 백엔드는
 * `uploaded_at DESC`로 정렬한다고 가정하지만(`VideoRepository.findAllByOrderByUploadedAtDesc`
 * 참조), 향후 캐싱 레이어나 관대한 어댑터가 예상치 못한 순서로 행을 반환할
 * 가능성에 대비해 UI가 다시 정렬한다 — 그래도 관리자는 항상 가장 최근
 * 업로드를 맨 위에서 본다.
 *
 * 백엔드가 2xx가 아닌 응답을 하면 `lib/api.ts`의 [ApiError]를 throw한다 —
 * 호출자(예: videos 페이지)는 이를 캐치하고 페이지를 크래시시키지 않고
 * 인라인 에러 안내를 렌더링한다.
 */
export async function listVideos(): Promise<VideoListItem[]> {
  const raw = await apiFetch<RawVideo[] | { items?: RawVideo[] }>(
    "/api/videos",
  );

  // 일부 Spring 컨트롤러는 컬렉션을 `{ items: [...] }`로 감싼다. 둘 다 수용.
  const items = Array.isArray(raw)
    ? raw
    : Array.isArray(raw?.items)
      ? raw.items
      : [];

  const normalised = items.map(normaliseVideo);

  // 방어적 최신순 정렬. falsy / 파싱 불가 타임스탬프는 맨 아래로 간다
  // (Date.parse → NaN은 아래에서 -Infinity로 취급).
  return normalised.slice().sort((a, b) => {
    const ta = Date.parse(a.uploadedAt);
    const tb = Date.parse(b.uploadedAt);
    const va = Number.isFinite(ta) ? ta : -Infinity;
    const vb = Number.isFinite(tb) ? tb : -Infinity;
    return vb - va;
  });
}

/** 파싱된 API 에러 본문에서 사람이 읽기 좋은 에러 메시지를 생성. */
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
