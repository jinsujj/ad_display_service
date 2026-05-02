/**
 * Ads / schedule API surface used by the admin web.
 *
 * AC 3, Sub-AC 3 scope:
 *   "Frontend - Build Next.js admin schedule form UI with datetime pickers
 *    and play count input on ad detail/edit page."
 *
 * Wire contract (Spring Boot backend, AC 3, Sub-AC 2 — see
 * `me/owldev/adsignage/domain/ad/AdController.kt`):
 *
 *   PUT  /api/ads/{id}/schedule
 *   PATCH /api/ads/{id}/schedule
 *     body: {
 *       "startTime":      string,   // "HH:mm"
 *       "endTime":        string,   // "HH:mm"  (must be > startTime)
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
 *   Errors are mapped by GlobalExceptionHandler:
 *     400 Bad Request    field validation OR cross-field "endTime <= startTime"
 *     401 Unauthorized   no/invalid JWT
 *     404 Not Found      ad id unknown OR not owned by the caller
 *
 * Design notes:
 *   - There is intentionally no GET /api/ads endpoint yet (out of Sub-AC 3
 *     scope), so this module exposes only the schedule-update verb. The form
 *     UI takes the ad id from the URL path (`/ads/[id]`) and submits a full
 *     schedule replacement.
 *   - Both PUT and PATCH are accepted server-side; the client uses PUT because
 *     the body is a complete schedule replacement (PUT semantics) — see the
 *     comment block on `AdController.putSchedule` for the rationale.
 */

import { apiFetch } from "./api";

/* --------------------------------------------------------------- types */

/** Wire shape returned by `PUT /api/ads/{id}/schedule`. */
export interface AdResponse {
  /** Server-generated UUID identifying the ad. */
  id: string;
  /** Owning advertiser's id (FK to `advertisers.id`). */
  advertiserId: string;
  /** Display title of the ad. */
  title: string;
  /** On-disk filename of the backing MP4 (FK to `videos.filename`). */
  videoFilename: string;
  /** Daily playback window start, "HH:mm" wall clock. */
  startTime: string;
  /** Daily playback window end, "HH:mm" wall clock. Strictly after `startTime`. */
  endTime: string;
  /** Target plays/day within the window. Always `>= 1`. */
  dailyPlayCount: number;
  /** ISO-8601 instant the ad row was originally created. */
  createdAt: string;
}

/** Request body for `PUT /api/ads/{id}/schedule`. */
export interface UpdateAdScheduleRequest {
  /** "HH:mm" wall clock — daily window start. */
  startTime: string;
  /** "HH:mm" wall clock — daily window end. Must be > startTime. */
  endTime: string;
  /** Target plays/day within the window. Server-validated `[1, 10000]`. */
  dailyPlayCount: number;
}

/* ------------------------------------------------- client-side validation */

/**
 * Bounds mirrored from the backend Bean Validation annotations on
 * `UpdateAdScheduleRequest` (`@Min(1) @Max(10_000)`). Kept in this module so
 * the form can fail fast before paying a network round-trip; the server is
 * the source of truth and re-validates on submit.
 */
export const DAILY_PLAY_COUNT_MIN = 1;
export const DAILY_PLAY_COUNT_MAX = 10_000;

/** Strict "HH:mm" guard — matches the backend's Jackson `pattern = "HH:mm"`. */
const HHMM_REGEX = /^(?:[01]\d|2[0-3]):[0-5]\d$/;

/** Validation result for [validateScheduleForm] — discriminated union. */
export type ScheduleValidationResult =
  | { ok: true }
  | {
      ok: false;
      /** Per-field error map keyed by request field name. */
      fieldErrors: Partial<
        Record<keyof UpdateAdScheduleRequest, string>
      >;
    };

/**
 * Validates an in-progress schedule form. Mirrors the backend's three failure
 * paths so the operator gets a synchronous, clearly-attributed error before we
 * issue the PUT:
 *   - missing / malformed `startTime` / `endTime`  (Bean Validation, 400)
 *   - `dailyPlayCount` out of `[1, 10000]`         (Bean Validation, 400)
 *   - cross-field `endTime <= startTime`           (service layer, 400)
 *
 * The backend remains authoritative on every rule — this is pure UX layering.
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

  // Cross-field: endTime must be strictly after startTime. Only run this
  // check if both fields are individually well-formed — otherwise we'd be
  // string-comparing "garbage > garbage" and adding noise.
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
 * Issues `PUT /api/ads/{id}/schedule` with a complete schedule replacement.
 *
 * Resolves with the persisted [AdResponse] (the wire shape returned by the
 * controller). Rejects with `ApiError` (from `lib/api.ts`) on non-2xx — the
 * caller (form component) renders the body inline so the operator sees the
 * specific Bean Validation `fieldErrors` map.
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
