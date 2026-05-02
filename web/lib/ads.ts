/**
 * 관리자 웹이 사용하는 광고/스케줄 API 표면.
 *
 * AC 3, Sub-AC 3 범위:
 *   "Frontend - 광고 상세/편집 페이지에서 datetime 피커와 재생 횟수 입력을
 *    포함한 Next.js 관리자 스케줄 폼 UI 구축."
 *
 * 와이어 계약(Spring Boot 백엔드, AC 3, Sub-AC 2 — 참조:
 * `me/owldev/adsignage/domain/ad/AdController.kt`):
 *
 *   PUT  /api/ads/{id}/schedule
 *   PATCH /api/ads/{id}/schedule
 *     body: {
 *       "startTime":      string,   // "HH:mm"
 *       "endTime":        string,   // "HH:mm"  (startTime보다 커야 함)
 *       "dailyPlayCount": number    // [1, 10000]
 *     }
 *   -> 200 OK, application/json
 *      {
 *        "id":             string,
 *        "advertiserId":   string,
 *        "title":          string,
 *        "videoFilename":  string,
 *        "startTime":      string,  // "HH:mm"
 *        "endTime":        string,  // "HH:mm"
 *        "dailyPlayCount": number,
 *        "createdAt":      string   // ISO-8601 instant
 *      }
 *
 *   에러는 GlobalExceptionHandler가 매핑한다:
 *     400 Bad Request    필드 검증 또는 교차 필드 "endTime <= startTime"
 *     401 Unauthorized   JWT 없음/유효하지 않음
 *     404 Not Found      ad id가 없거나 호출자가 소유자가 아님
 *
 * 설계 메모:
 *   - 의도적으로 GET /api/ads 엔드포인트는 아직 없다(Sub-AC 3 범위 외)므로,
 *     이 모듈은 스케줄 갱신 동작만 노출한다. 폼 UI는 URL 경로(`/ads/[id]`)에서
 *     ad id를 가져와 전체 스케줄 교체로 제출한다.
 *   - 서버는 PUT과 PATCH를 모두 허용하지만, 본문이 스케줄 전체 교체이므로
 *     클라이언트는 PUT을 사용한다(PUT 시맨틱) — 근거는 `AdController.putSchedule`의
 *     주석 블록 참조.
 */

import { apiFetch } from "./api";

/* --------------------------------------------------------------- types */

/** `PUT /api/ads/{id}/schedule`이 반환하는 와이어 형태. */
export interface AdResponse {
  /** 광고를 식별하는 서버 생성 UUID. */
  id: string;
  /** 소유 광고주 id(FK는 `advertisers.id`). */
  advertiserId: string;
  /** 광고의 표시 제목. */
  title: string;
  /** 백킹 MP4의 디스크 파일명(FK는 `videos.filename`). */
  videoFilename: string;
  /** 일일 재생 윈도우 시작, "HH:mm" 벽시계 시각. */
  startTime: string;
  /** 일일 재생 윈도우 종료, "HH:mm" 벽시계 시각. `startTime`보다 엄격히 큼. */
  endTime: string;
  /** 윈도우 내 목표 일일 재생 수. 항상 `>= 1`. */
  dailyPlayCount: number;
  /** 광고 행이 최초 생성된 ISO-8601 instant. */
  createdAt: string;
}

/** `PUT /api/ads/{id}/schedule`의 요청 본문. */
export interface UpdateAdScheduleRequest {
  /** "HH:mm" 벽시계 시각 — 일일 윈도우 시작. */
  startTime: string;
  /** "HH:mm" 벽시계 시각 — 일일 윈도우 종료. startTime보다 커야 한다. */
  endTime: string;
  /** 윈도우 내 목표 일일 재생 수. 서버에서 `[1, 10000]`로 검증. */
  dailyPlayCount: number;
}

/* ------------------------------------------------- client-side validation */

/**
 * `UpdateAdScheduleRequest`의 백엔드 Bean Validation 어노테이션
 * (`@Min(1) @Max(10_000)`)을 그대로 반영한 경계값. 폼이 네트워크 왕복 비용을
 * 들이지 않고 빠르게 실패할 수 있도록 이 모듈에 둔다. 서버가 진실의 출처이며
 * 제출 시 재검증한다.
 */
export const DAILY_PLAY_COUNT_MIN = 1;
export const DAILY_PLAY_COUNT_MAX = 10_000;

/** 엄격한 "HH:mm" 가드 — 백엔드의 Jackson `pattern = "HH:mm"`과 일치. */
const HHMM_REGEX = /^(?:[01]\d|2[0-3]):[0-5]\d$/;

/** [validateScheduleForm]의 검증 결과 — 식별 유니온. */
export type ScheduleValidationResult =
  | { ok: true }
  | {
      ok: false;
      /** 요청 필드명을 키로 갖는 필드별 에러 맵. */
      fieldErrors: Partial<
        Record<keyof UpdateAdScheduleRequest, string>
      >;
    };

/**
 * 작성 중인 스케줄 폼을 검증한다. PUT을 보내기 전에 운영자에게 동기적으로
 * 명확히 귀속된 에러를 제공하기 위해 백엔드의 세 가지 실패 경로를 그대로
 * 반영한다:
 *   - 누락 / 형식 오류 `startTime` / `endTime`     (Bean Validation, 400)
 *   - `dailyPlayCount`가 `[1, 10000]` 범위 밖     (Bean Validation, 400)
 *   - 교차 필드 `endTime <= startTime`            (서비스 계층, 400)
 *
 * 모든 규칙의 권위는 백엔드에 있다 — 이것은 순전히 UX 레이어다.
 */
export function validateScheduleForm(
  body: Partial<UpdateAdScheduleRequest>,
): ScheduleValidationResult {
  const errors: Partial<Record<keyof UpdateAdScheduleRequest, string>> = {};

  if (!body.startTime || !HHMM_REGEX.test(body.startTime)) {
    errors.startTime = "Start time is required (HH:mm).";
  }
  if (!body.endTime || !HHMM_REGEX.test(body.endTime)) {
    errors.endTime = "End time is required (HH:mm).";
  }
  if (
    body.dailyPlayCount === undefined ||
    body.dailyPlayCount === null ||
    Number.isNaN(body.dailyPlayCount)
  ) {
    errors.dailyPlayCount = "Daily play count is required.";
  } else if (!Number.isInteger(body.dailyPlayCount)) {
    errors.dailyPlayCount = "Daily play count must be a whole number.";
  } else if (body.dailyPlayCount < DAILY_PLAY_COUNT_MIN) {
    errors.dailyPlayCount =
      `Daily play count must be at least ${DAILY_PLAY_COUNT_MIN}.`;
  } else if (body.dailyPlayCount > DAILY_PLAY_COUNT_MAX) {
    errors.dailyPlayCount =
      `Daily play count must be at most ${DAILY_PLAY_COUNT_MAX}.`;
  }

  // 교차 필드: endTime은 startTime보다 엄격히 커야 한다. 두 필드가 각각
  // 올바른 형식일 때만 이 검사를 수행 — 그렇지 않으면 "쓰레기 > 쓰레기"의
  // 문자열 비교가 되어 노이즈만 늘어난다.
  if (
    !errors.startTime &&
    !errors.endTime &&
    body.startTime &&
    body.endTime &&
    body.endTime <= body.startTime
  ) {
    errors.endTime = "End time must be after start time.";
  }

  if (Object.keys(errors).length === 0) return { ok: true };
  return { ok: false, fieldErrors: errors };
}

/* ------------------------------------------------------ HTTP wrapper */

/**
 * 전체 스케줄 교체로 `PUT /api/ads/{id}/schedule`을 호출한다.
 *
 * 저장된 [AdResponse](컨트롤러가 반환하는 와이어 형태)로 resolve한다. 2xx가
 * 아니면 `ApiError`(`lib/api.ts`)로 reject한다 — 호출자(폼 컴포넌트)는 본문을
 * 인라인으로 렌더링하여 운영자가 구체적인 Bean Validation `fieldErrors` 맵을
 * 보게 한다.
 */
export async function updateAdSchedule(
  adId: string,
  body: UpdateAdScheduleRequest,
): Promise<AdResponse> {
  if (!adId) throw new Error("adId is required");
  return apiFetch<AdResponse>(
    `/api/ads/${encodeURIComponent(adId)}/schedule`,
    {
      method: "PUT",
      body,
    },
  );
}
