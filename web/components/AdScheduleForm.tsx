"use client";

/**
 * 광고 스케줄 폼 (AC 3, Sub-AC 3).
 *
 * 일일 시계 윈도우(startTime, endTime) + 일일 송출 횟수 + 캠페인 기간을
 * PUT /api/ads/{id}/schedule 로 저장. validateScheduleForm 동기 검증 그대로.
 */

import { useCallback, useMemo, useState } from "react";
import { useRouter } from "next/navigation";

import { ApiError } from "@/lib/api";
import {
  DAILY_PLAY_COUNT_MAX,
  DAILY_PLAY_COUNT_MIN,
  type AdResponse,
  type UpdateAdScheduleRequest,
  updateAdSchedule,
  validateScheduleForm,
} from "@/lib/ads";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";

export interface AdScheduleFormInitialValues {
  startTime?: string;
  endTime?: string;
  dailyPlayCount?: number;
  campaignStartDate?: string;
  campaignEndDate?: string;
}

export interface AdScheduleFormProps {
  adId: string;
  initialValues?: AdScheduleFormInitialValues;
  onSaved?: (saved: AdResponse) => void;
}

type SubmitState =
  | { kind: "idle" }
  | { kind: "submitting" }
  | { kind: "success"; result: AdResponse }
  | {
      kind: "error";
      message: string;
      status: number | null;
      fieldErrors?: Partial<Record<keyof UpdateAdScheduleRequest, string>>;
    };

export function AdScheduleForm(props: AdScheduleFormProps) {
  const { adId, initialValues, onSaved } = props;
  const router = useRouter();

  const [startTime, setStartTime] = useState<string>(
    initialValues?.startTime ?? "",
  );
  const [endTime, setEndTime] = useState<string>(initialValues?.endTime ?? "");
  const [dailyPlayCountStr, setDailyPlayCountStr] = useState<string>(
    initialValues?.dailyPlayCount !== undefined
      ? String(initialValues.dailyPlayCount)
      : "",
  );
  const [campaignStartDate, setCampaignStartDate] = useState<string>(
    initialValues?.campaignStartDate ?? "",
  );
  const [campaignEndDate, setCampaignEndDate] = useState<string>(
    initialValues?.campaignEndDate ?? "",
  );

  const [clientErrors, setClientErrors] = useState<
    Partial<Record<keyof UpdateAdScheduleRequest, string>>
  >({});

  const [submitState, setSubmitState] = useState<SubmitState>({ kind: "idle" });

  const wrapEdit = useCallback(
    <V,>(
        setter: (v: V) => void,
        field: keyof UpdateAdScheduleRequest,
      ) =>
      (value: V) => {
        setter(value);
        setSubmitState((prev) =>
          prev.kind === "idle" ? prev : { kind: "idle" },
        );
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

  const parsedBody = useMemo<Partial<UpdateAdScheduleRequest>>(
    () => ({
      startTime: startTime || undefined,
      endTime: endTime || undefined,
      dailyPlayCount:
        dailyPlayCountStr.trim() === ""
          ? undefined
          : Number(dailyPlayCountStr),
    }),
    [startTime, endTime, dailyPlayCountStr],
  );

  const submitting = submitState.kind === "submitting";

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

      const campaignDatesValid =
        !!campaignStartDate &&
        !!campaignEndDate &&
        campaignEndDate >= campaignStartDate;

      const body: UpdateAdScheduleRequest = {
        startTime: parsedBody.startTime!,
        endTime: parsedBody.endTime!,
        dailyPlayCount: parsedBody.dailyPlayCount!,
        ...(campaignDatesValid
          ? { campaignStartDate, campaignEndDate }
          : {}),
      };

      setSubmitState({ kind: "submitting" });
      try {
        const result = await updateAdSchedule(adId, body);
        setSubmitState({ kind: "success", result });
        // 후속 편집이 "실제 저장된 것"에서 시작하도록 폼 재기준선화.
        setStartTime(result.startTime);
        setEndTime(result.endTime);
        setDailyPlayCountStr(String(result.dailyPlayCount));
        onSaved?.(result);
      } catch (err) {
        setSubmitState(buildErrorState(err));
      }
    },
    [
      adId,
      parsedBody,
      campaignStartDate,
      campaignEndDate,
      submitting,
      onSaved,
    ],
  );

  const startError =
    clientErrors.startTime ??
    (submitState.kind === "error"
      ? submitState.fieldErrors?.startTime
      : undefined);
  const endError =
    clientErrors.endTime ??
    (submitState.kind === "error"
      ? submitState.fieldErrors?.endTime
      : undefined);
  const countError =
    clientErrors.dailyPlayCount ??
    (submitState.kind === "error"
      ? submitState.fieldErrors?.dailyPlayCount
      : undefined);

  return (
    <Card>
      <CardHeader>
        <CardTitle>일일 송출 스케줄</CardTitle>
        <p className="text-sm text-muted-foreground">
          광고가 송출될 일일 시계 윈도우와 그 안에서의 목표 송출 횟수를
          정합니다. 스케줄은 광고의 현재 설정을 전체 교체합니다(PUT 시맨틱).
        </p>
      </CardHeader>
      <CardContent>
        <form className="space-y-4" onSubmit={handleSubmit} noValidate>
          <fieldset className="space-y-4" disabled={submitting}>
            <div className="grid gap-4 md:grid-cols-3">
              <ScheduleField
                id="ad-schedule-start"
                label="시작 시간"
                error={startError}
              >
                <Input
                  id="ad-schedule-start"
                  name="startTime"
                  type="time"
                  required
                  step={60}
                  value={startTime}
                  onChange={(e) => onStartTimeChange(e.target.value)}
                  aria-invalid={Boolean(startError) || undefined}
                />
              </ScheduleField>
              <ScheduleField
                id="ad-schedule-end"
                label="종료 시간"
                error={endError}
              >
                <Input
                  id="ad-schedule-end"
                  name="endTime"
                  type="time"
                  required
                  step={60}
                  value={endTime}
                  onChange={(e) => onEndTimeChange(e.target.value)}
                  aria-invalid={Boolean(endError) || undefined}
                />
              </ScheduleField>
              <ScheduleField
                id="ad-schedule-count"
                label={
                  <>
                    일일 송출 횟수{" "}
                    <span className="text-xs font-normal text-muted-foreground">
                      · {DAILY_PLAY_COUNT_MIN}–{DAILY_PLAY_COUNT_MAX}
                    </span>
                  </>
                }
                error={countError}
              >
                <Input
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
                  aria-invalid={Boolean(countError) || undefined}
                />
              </ScheduleField>
            </div>

            <div>
              <h3 className="mt-2 text-xs font-semibold uppercase tracking-wider text-muted-foreground">
                캠페인 기간{" "}
                <span className="font-normal normal-case tracking-normal">
                  · 이 기간이 지나면 자동으로 송출이 중단됩니다
                </span>
              </h3>
              <div className="mt-2 grid gap-4 md:grid-cols-2">
                <ScheduleField id="ad-schedule-camp-start" label="시작일">
                  <Input
                    id="ad-schedule-camp-start"
                    type="date"
                    value={campaignStartDate}
                    onChange={(e) => setCampaignStartDate(e.target.value)}
                  />
                </ScheduleField>
                <ScheduleField id="ad-schedule-camp-end" label="종료일">
                  <Input
                    id="ad-schedule-camp-end"
                    type="date"
                    value={campaignEndDate}
                    onChange={(e) => setCampaignEndDate(e.target.value)}
                  />
                </ScheduleField>
              </div>
            </div>

            {submitState.kind === "error" && (
              <Alert variant="destructive">
                <AlertDescription>
                  <strong>스케줄 저장에 실패했습니다.</strong>{" "}
                  {submitState.status ? `[HTTP ${submitState.status}] ` : ""}
                  {submitState.message}
                  {submitState.status === 401 && (
                    <div className="mt-1.5 text-xs opacity-90">
                      스케줄 엔드포인트는 인증이 필요합니다. 로그인 후 다시
                      시도해 주세요.
                    </div>
                  )}
                </AlertDescription>
              </Alert>
            )}

            {submitState.kind === "success" && (
              <Alert variant="ok">
                <AlertDescription>
                  <strong>스케줄이 저장되었습니다.</strong>{" "}
                  {submitState.result.startTime}–{submitState.result.endTime} ·{" "}
                  {submitState.result.dailyPlayCount} 회/일 — 다음 플레이리스트
                  새로고침에 반영됩니다.
                </AlertDescription>
              </Alert>
            )}

            <div className="flex flex-col gap-2 sm:flex-row sm:justify-end">
              <Button
                type="button"
                variant="outline"
                disabled={submitting}
                onClick={() => router.push("/ads")}
                className="w-full sm:w-auto"
              >
                취소
              </Button>
              <Button
                type="submit"
                disabled={submitting}
                aria-busy={submitting}
                className="w-full sm:w-auto"
              >
                {submitting ? "저장 중…" : "스케줄 저장"}
              </Button>
            </div>
          </fieldset>
        </form>
      </CardContent>
    </Card>
  );
}

interface ScheduleFieldProps {
  id: string;
  label: React.ReactNode;
  error?: string;
  children: React.ReactNode;
}

function ScheduleField({ id, label, error, children }: ScheduleFieldProps) {
  return (
    <div className="space-y-1.5">
      <Label htmlFor={id} className="flex flex-wrap items-baseline gap-2">
        {label}
      </Label>
      {children}
      {error && (
        <p className="text-xs text-destructive" role="alert">
          {error}
        </p>
      )}
    </div>
  );
}

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
      body?.message?.trim() || `Request failed with HTTP ${err.status}.`;

    const fieldErrors: Partial<Record<keyof UpdateAdScheduleRequest, string>> =
      {};
    if (body?.fieldErrors && typeof body.fieldErrors === "object") {
      const fe = body.fieldErrors;
      if (typeof fe.startTime === "string")
        fieldErrors.startTime = fe.startTime;
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
