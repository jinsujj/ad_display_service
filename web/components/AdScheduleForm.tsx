"use client";

/**
 * Ad schedule form (AC 3, Sub-AC 3).
 *
 * Goal:
 *   "Build Next.js admin schedule form UI with datetime pickers and play
 *    count input on ad detail/edit page."
 *
 * What this component does:
 *
 *   1. Renders three controls bound to the
 *      `me.owldev.adsignage.domain.ad.dto.UpdateAdScheduleRequest` shape:
 *        - `<input type="time">` for `startTime` ("HH:mm")
 *        - `<input type="time">` for `endTime`   ("HH:mm")
 *        - `<input type="number">` for `dailyPlayCount` (integer, 1..10000)
 *
 *      `<input type="time">` is the right "datetime picker" primitive for
 *      this domain. The schedule fields are *daily wall-clock windows* —
 *      [LocalTime] on the server, no date component. The native widget
 *      gives us platform-correct pickers (spinner on iOS, dropdown on
 *      Chrome desktop, time wheel on Android WebView) for free, with no
 *      extra deps.
 *
 *   2. On submit:
 *        - runs [validateScheduleForm] for synchronous field-level + cross-
 *          field error reporting (matches the backend's Bean Validation +
 *          service-layer cross-field rule);
 *        - on validation fail: flags the offending fields inline,
 *          DOES NOT issue the request;
 *        - on validation pass: PUT /api/ads/{id}/schedule, then renders a
 *          success notice with the persisted AdResponse;
 *        - on API failure: surfaces the backend's `message` and (where
 *          present) per-field `fieldErrors` map so the operator sees the
 *          authoritative server-side rejection reason without having to
 *          open devtools.
 *
 *   3. Accepts an `initialValues` prop so the page can pre-populate the
 *      form with the ad's current schedule on first render. After a
 *      successful submit the form re-baselines onto the response so a
 *      subsequent edit starts from the just-persisted state, not the
 *      previously-rendered server value.
 *
 * Why a Client Component:
 *   The form needs `useState` for controlled inputs, browser-only
 *   validation, and `fetch` to issue the PUT — all of which require the
 *   client runtime. The wrapper page (`app/ads/[id]/page.tsx`) stays a
 *   Server Component so the page shell renders without JS.
 */

import { useCallback, useMemo, useState } from "react";

import { ApiError } from "@/lib/api";
import {
  DAILY_PLAY_COUNT_MAX,
  DAILY_PLAY_COUNT_MIN,
  type AdResponse,
  type UpdateAdScheduleRequest,
  updateAdSchedule,
  validateScheduleForm,
} from "@/lib/ads";

/* ------------------------------------------------------ props / state */

/**
 * Pre-populated form values. The page passes whatever it knows about the ad
 * (typically nothing, since there is no GET /api/ads endpoint yet) so the
 * operator can either start from blank fields or be primed with a sensible
 * default like the canonical 09:00–23:00 daypart restaurants run on.
 */
export interface AdScheduleFormInitialValues {
  startTime?: string; // "HH:mm"
  endTime?: string;   // "HH:mm"
  dailyPlayCount?: number;
}

/** Props for [AdScheduleForm]. */
export interface AdScheduleFormProps {
  /** UUID of the ad whose schedule is being edited. Required. */
  adId: string;
  /** Optional pre-populated values to seed the controlled inputs. */
  initialValues?: AdScheduleFormInitialValues;
  /**
   * Called after every successful PUT so the parent (e.g. the detail page)
   * can refresh its surrounding state. Receives the persisted AdResponse.
   */
  onSaved?: (saved: AdResponse) => void;
}

/** Submit lifecycle. */
type SubmitState =
  | { kind: "idle" }
  | { kind: "submitting" }
  | { kind: "success"; result: AdResponse }
  | {
      kind: "error";
      message: string;
      status: number | null;
      /** Server-side field errors keyed by request field name. */
      fieldErrors?: Partial<Record<keyof UpdateAdScheduleRequest, string>>;
    };

/* ------------------------------------------------------ component */

export function AdScheduleForm(props: AdScheduleFormProps) {
  const { adId, initialValues, onSaved } = props;

  // Controlled-input state. We deliberately keep `dailyPlayCount` as a string
  // so the user can briefly hold an empty input mid-typing — `Number("")` is
  // `0` which would clobber the validator's "required" branch.
  const [startTime, setStartTime] = useState<string>(
    initialValues?.startTime ?? "",
  );
  const [endTime, setEndTime] = useState<string>(initialValues?.endTime ?? "");
  const [dailyPlayCountStr, setDailyPlayCountStr] = useState<string>(
    initialValues?.dailyPlayCount !== undefined
      ? String(initialValues.dailyPlayCount)
      : "",
  );

  // Per-field validation errors (synchronous, before the request).
  const [clientErrors, setClientErrors] = useState<
    Partial<Record<keyof UpdateAdScheduleRequest, string>>
  >({});

  const [submitState, setSubmitState] = useState<SubmitState>({ kind: "idle" });

  /**
   * Wraps a setter so that any post-submit notice (success OR error) is
   * cleared the moment the user starts editing again — otherwise a stale
   * "Schedule saved." banner would linger even after the operator typed a
   * new value, misrepresenting the current persisted state. Also clears the
   * matching per-field client error so the inline message disappears as soon
   * as the user attempts a fix.
   */
  const wrapEdit = useCallback(
    <V,>(
      setter: (v: V) => void,
      field: keyof UpdateAdScheduleRequest,
    ) => (value: V) => {
      setter(value);
      setSubmitState((prev) => (prev.kind === "idle" ? prev : { kind: "idle" }));
      setClientErrors((prev) =>
        prev[field] === undefined ? prev : { ...prev, [field]: undefined },
      );
    },
    [],
  );

  const onStartTimeChange = wrapEdit<string>(setStartTime, "startTime");
  const onEndTimeChange = wrapEdit<string>(setEndTime, "endTime");
  const onDailyPlayCountChange = wrapEdit<string>(
    setDailyPlayCountStr,
    "dailyPlayCount",
  );

  /**
   * Coerce the controlled state into a typed request body for the validator
   * + the network call. `dailyPlayCount` becomes `NaN` if the input is empty
   * or malformed, which the validator treats as "missing".
   */
  const parsedBody = useMemo<Partial<UpdateAdScheduleRequest>>(
    () => ({
      startTime: startTime || undefined,
      endTime: endTime || undefined,
      dailyPlayCount:
        dailyPlayCountStr.trim() === "" ? undefined : Number(dailyPlayCountStr),
    }),
    [startTime, endTime, dailyPlayCountStr],
  );

  const submitting = submitState.kind === "submitting";

  /* -------- submit */

  const handleSubmit = useCallback(
    async (event: React.FormEvent<HTMLFormElement>) => {
      event.preventDefault();
      if (submitting) return;

      const validation = validateScheduleForm(parsedBody);
      if (!validation.ok) {
        setClientErrors(validation.fieldErrors);
        setSubmitState({ kind: "idle" });
        return;
      }
      setClientErrors({});

      // After validation we know all three fields are present and well-formed.
      const body: UpdateAdScheduleRequest = {
        startTime: parsedBody.startTime!,
        endTime: parsedBody.endTime!,
        dailyPlayCount: parsedBody.dailyPlayCount!,
      };

      setSubmitState({ kind: "submitting" });
      try {
        const result = await updateAdSchedule(adId, body);
        setSubmitState({ kind: "success", result });
        // Re-baseline the form on the server-confirmed state so a follow-up
        // edit starts from "what's actually saved", not the previous initial
        // values prop.
        setStartTime(result.startTime);
        setEndTime(result.endTime);
        setDailyPlayCountStr(String(result.dailyPlayCount));
        onSaved?.(result);
      } catch (err) {
        setSubmitState(buildErrorState(err));
      }
    },
    [adId, parsedBody, submitting, onSaved],
  );

  /* -------- derived UI bits */

  const startError =
    clientErrors.startTime ??
    (submitState.kind === "error" ? submitState.fieldErrors?.startTime : undefined);
  const endError =
    clientErrors.endTime ??
    (submitState.kind === "error" ? submitState.fieldErrors?.endTime : undefined);
  const countError =
    clientErrors.dailyPlayCount ??
    (submitState.kind === "error"
      ? submitState.fieldErrors?.dailyPlayCount
      : undefined);

  /* -------- render */

  return (
    <form className="schedule-form" onSubmit={handleSubmit} noValidate>
      <fieldset className="schedule-form__fieldset" disabled={submitting}>
        <legend className="schedule-form__legend">Daily playback schedule</legend>
        <p className="muted schedule-form__hint">
          Pick the daily wall-clock window the ad should play within and the
          target number of plays inside that window. The schedule replaces the
          ad&apos;s current configuration in full (PUT semantics).
        </p>

        <div className="schedule-form__grid">
          {/* startTime */}
          <div className="schedule-form__field">
            <label htmlFor="ad-schedule-start" className="schedule-form__label">
              Start time
            </label>
            <input
              id="ad-schedule-start"
              name="startTime"
              type="time"
              required
              step={60}
              value={startTime}
              onChange={(e) => onStartTimeChange(e.target.value)}
              className="schedule-form__input"
              aria-invalid={Boolean(startError) || undefined}
              aria-describedby={startError ? "ad-schedule-start-err" : undefined}
            />
            {startError && (
              <div
                id="ad-schedule-start-err"
                className="schedule-form__field-error"
                role="alert"
              >
                {startError}
              </div>
            )}
          </div>

          {/* endTime */}
          <div className="schedule-form__field">
            <label htmlFor="ad-schedule-end" className="schedule-form__label">
              End time
            </label>
            <input
              id="ad-schedule-end"
              name="endTime"
              type="time"
              required
              step={60}
              value={endTime}
              onChange={(e) => onEndTimeChange(e.target.value)}
              className="schedule-form__input"
              aria-invalid={Boolean(endError) || undefined}
              aria-describedby={endError ? "ad-schedule-end-err" : undefined}
            />
            {endError && (
              <div
                id="ad-schedule-end-err"
                className="schedule-form__field-error"
                role="alert"
              >
                {endError}
              </div>
            )}
          </div>

          {/* dailyPlayCount */}
          <div className="schedule-form__field">
            <label htmlFor="ad-schedule-count" className="schedule-form__label">
              Daily play count
              <span className="muted schedule-form__hint">
                {" "}
                · {DAILY_PLAY_COUNT_MIN}–{DAILY_PLAY_COUNT_MAX}
              </span>
            </label>
            <input
              id="ad-schedule-count"
              name="dailyPlayCount"
              type="number"
              required
              inputMode="numeric"
              min={DAILY_PLAY_COUNT_MIN}
              max={DAILY_PLAY_COUNT_MAX}
              step={1}
              value={dailyPlayCountStr}
              onChange={(e) => onDailyPlayCountChange(e.target.value)}
              className="schedule-form__input"
              aria-invalid={Boolean(countError) || undefined}
              aria-describedby={countError ? "ad-schedule-count-err" : undefined}
            />
            {countError && (
              <div
                id="ad-schedule-count-err"
                className="schedule-form__field-error"
                role="alert"
              >
                {countError}
              </div>
            )}
          </div>
        </div>

        {/* form-level error */}
        {submitState.kind === "error" && (
          <div className="notice notice-error" role="alert">
            <strong>Failed to save schedule.</strong>{" "}
            {submitState.status ? `[HTTP ${submitState.status}] ` : ""}
            {submitState.message}
            {submitState.status === 401 && (
              <div className="muted" style={{ marginTop: 6 }}>
                The schedule endpoint requires authentication. Sign in (or
                store a JWT under <code>localStorage.adsignage_auth_token</code>)
                and retry.
              </div>
            )}
          </div>
        )}

        {/* success */}
        {submitState.kind === "success" && (
          <div
            className="notice"
            role="status"
            style={{
              borderColor: "rgba(74, 222, 128, 0.5)",
              background: "rgba(74, 222, 128, 0.08)",
              color: "var(--ok)",
            }}
          >
            <strong>Schedule saved.</strong> {submitState.result.startTime}–
            {submitState.result.endTime} · {submitState.result.dailyPlayCount}{" "}
            plays/day. The next playlist refresh will reflect the new window.
          </div>
        )}

        <div className="toolbar schedule-form__actions">
          <button type="submit" className="btn" disabled={submitting} aria-busy={submitting}>
            {submitting ? "Saving…" : "Save schedule"}
          </button>
        </div>
      </fieldset>
    </form>
  );
}

/* ------------------------------------------------------ helpers */

/**
 * Translate a thrown error from `updateAdSchedule` into a [SubmitState] of
 * `kind: "error"`. We do our best to extract the backend's structured
 * `fieldErrors` map so the form can re-attribute server-side rejections to
 * the offending control(s) (e.g. cross-field "endTime > startTime" lands on
 * the End time input).
 */
function buildErrorState(err: unknown): SubmitState {
  if (err instanceof ApiError) {
    const body = err.body as
      | {
          message?: string;
          fieldErrors?: Record<string, string> | null;
        }
      | undefined
      | null;

    const message =
      body?.message?.trim() ||
      `Request failed with HTTP ${err.status}.`;

    const fieldErrors: Partial<Record<keyof UpdateAdScheduleRequest, string>> = {};
    if (body?.fieldErrors && typeof body.fieldErrors === "object") {
      const fe = body.fieldErrors;
      if (typeof fe.startTime === "string") fieldErrors.startTime = fe.startTime;
      if (typeof fe.endTime === "string") fieldErrors.endTime = fe.endTime;
      if (typeof fe.dailyPlayCount === "string")
        fieldErrors.dailyPlayCount = fe.dailyPlayCount;
    }

    return {
      kind: "error",
      message,
      status: err.status,
      fieldErrors:
        Object.keys(fieldErrors).length > 0 ? fieldErrors : undefined,
    };
  }
  if (err instanceof Error) {
    return { kind: "error", message: err.message, status: null };
  }
  return { kind: "error", message: "Unknown error", status: null };
}

export default AdScheduleForm;
