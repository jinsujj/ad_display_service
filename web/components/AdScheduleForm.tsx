"use client";

/**
 * 광고 스케줄 폼 (AC 3, Sub-AC 3).
 *
 * 목표:
 *   "Build Next.js admin schedule form UI with datetime pickers and play
 *    count input on ad detail/edit page."
 *
 * 이 컴포넌트가 하는 일:
 *
 *   1. `me.owldev.adsignage.domain.ad.dto.UpdateAdScheduleRequest` 형태에
 *      바인딩된 3개 컨트롤 렌더링:
 *        - `startTime`("HH:mm")용 `<input type="time">`
 *        - `endTime`("HH:mm")용 `<input type="time">`
 *        - `dailyPlayCount`(정수, 1..10000)용 `<input type="number">`
 *
 *      `<input type="time">`은 이 도메인에 적합한 "datetime picker"
 *      프리미티브다. 스케줄 필드는 *일일 벽시계 윈도우* — 서버 측의
 *      [LocalTime]이며 날짜 컴포넌트 없음. 네이티브 위젯이 추가 의존성
 *      없이 플랫폼별 정확한 피커(iOS의 스피너, Chrome 데스크톱의 드롭다운,
 *      Android WebView의 시간 휠)를 무료로 제공한다.
 *
 *   2. 제출 시:
 *        - 동기 필드 레벨 + 크로스 필드 오류 보고를 위해
 *          [validateScheduleForm] 실행(백엔드의 Bean Validation +
 *          서비스 레이어 크로스 필드 규칙과 일치);
 *        - 검증 실패: 문제 필드를 인라인으로 플래그하고 요청은 *발행하지
 *          않음*;
 *        - 검증 통과: PUT /api/ads/{id}/schedule 후 영속화된 AdResponse로
 *          성공 알림 렌더링;
 *        - API 실패: 백엔드의 `message`와 (있으면) 필드별 `fieldErrors`
 *          맵을 노출해 운영자가 devtools를 열지 않고도 권위 있는 서버 측
 *          거절 사유를 본다.
 *
 *   3. `initialValues` prop을 받아 페이지가 첫 렌더에서 광고의 현재 스케줄로
 *      폼을 미리 채울 수 있게 한다. 성공한 제출 후 폼은 응답으로 재기준선화
 *      되어 다음 편집이 이전에 렌더된 서버 값이 아닌 방금 영속화된 상태에서
 *      시작한다.
 *
 * 왜 클라이언트 컴포넌트인가:
 *   폼은 컨트롤드 입력을 위한 `useState`, 브라우저 전용 검증, PUT을 발행할
 *   `fetch`가 필요하며 — 모두 클라이언트 런타임을 요구한다. 래퍼 페이지
 *   (`app/ads/[id]/page.tsx`)는 서버 컴포넌트로 유지되어 페이지 셸이 JS
 *   없이 렌더링된다.
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
 * 미리 채워진 폼 값. 페이지가 광고에 대해 아는 것을 무엇이든 전달한다
 * (보통 아무것도 — 아직 GET /api/ads 엔드포인트가 없으므로). 운영자는 빈
 * 필드에서 시작하거나 음식점이 운영하는 정식 09:00–23:00 같은 합리적
 * 기본값으로 시작할 수 있다.
 */
export interface AdScheduleFormInitialValues {
  startTime?: string; // "HH:mm"
  endTime?: string;   // "HH:mm"
  dailyPlayCount?: number;
}

/** [AdScheduleForm]의 props. */
export interface AdScheduleFormProps {
  /** 스케줄을 편집 중인 광고의 UUID. 필수. */
  adId: string;
  /** 컨트롤드 입력에 시드할 미리 채워진 값(선택). */
  initialValues?: AdScheduleFormInitialValues;
  /**
   * 모든 성공한 PUT 후 호출되어 부모(예: 상세 페이지)가 주변 상태를
   * 새로고침할 수 있게 한다. 영속화된 AdResponse를 받는다.
   */
  onSaved?: (saved: AdResponse) => void;
}

/** 제출 라이프사이클. */
type SubmitState =
  | { kind: "idle" }
  | { kind: "submitting" }
  | { kind: "success"; result: AdResponse }
  | {
      kind: "error";
      message: string;
      status: number | null;
      /** 요청 필드 이름으로 키된 서버 측 필드 오류. */
      fieldErrors?: Partial<Record<keyof UpdateAdScheduleRequest, string>>;
    };

/* ------------------------------------------------------ component */

export function AdScheduleForm(props: AdScheduleFormProps) {
  const { adId, initialValues, onSaved } = props;

  // 컨트롤드 입력 상태. 사용자가 타이핑 도중 잠시 빈 입력을 유지할 수
  // 있도록 `dailyPlayCount`를 의도적으로 문자열로 둔다 — `Number("")`는
  // `0`이며 검증기의 "required" 분기를 망가뜨린다.
  const [startTime, setStartTime] = useState<string>(
    initialValues?.startTime ?? "",
  );
  const [endTime, setEndTime] = useState<string>(initialValues?.endTime ?? "");
  const [dailyPlayCountStr, setDailyPlayCountStr] = useState<string>(
    initialValues?.dailyPlayCount !== undefined
      ? String(initialValues.dailyPlayCount)
      : "",
  );

  // 필드별 검증 오류(요청 전 동기).
  const [clientErrors, setClientErrors] = useState<
    Partial<Record<keyof UpdateAdScheduleRequest, string>>
  >({});

  const [submitState, setSubmitState] = useState<SubmitState>({ kind: "idle" });

  /**
   * 사용자가 다시 편집을 시작하는 순간 제출 후 알림(성공 OR 오류)이
   * 지워지도록 setter를 감싼다 — 그렇지 않으면 운영자가 새 값을 타이핑한
   * 후에도 stale "Schedule saved." 배너가 남아 현재 영속화된 상태를 잘못
   * 표현한다. 사용자가 수정을 시도하는 즉시 인라인 메시지가 사라지도록
   * 매칭되는 필드별 클라이언트 오류도 지운다.
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
   * 컨트롤드 상태를 검증기와 네트워크 호출에 적합한 타입화된 요청 본문으로
   * 변환. 입력이 비어있거나 형식이 잘못된 경우 `dailyPlayCount`는 `NaN`이
   * 되며 검증기는 이를 "missing"으로 다룬다.
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

      // 검증 후에는 세 필드가 모두 존재하고 형식이 올바름을 안다.
      const body: UpdateAdScheduleRequest = {
        startTime: parsedBody.startTime!,
        endTime: parsedBody.endTime!,
        dailyPlayCount: parsedBody.dailyPlayCount!,
      };

      setSubmitState({ kind: "submitting" });
      try {
        const result = await updateAdSchedule(adId, body);
        setSubmitState({ kind: "success", result });
        // 후속 편집이 "실제로 저장된 것"에서 시작하도록 서버 확인 상태로
        // 폼을 재기준선화 — 이전 initialValues prop이 아니라.
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

  /* -------- 파생 UI 비트 */

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

  /* -------- 렌더 */

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

        {/* 폼 레벨 오류 */}
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

        {/* 성공 */}
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
 * `updateAdSchedule`에서 throw된 오류를 `kind: "error"`의 [SubmitState]로
 * 변환. 폼이 서버 측 거절을 문제 컨트롤(들)에 재귀속할 수 있도록(예:
 * 크로스 필드 "endTime > startTime"이 End time 입력에 떨어짐) 백엔드의
 * 구조화된 `fieldErrors` 맵을 최선을 다해 추출한다.
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
