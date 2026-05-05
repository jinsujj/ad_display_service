"use client";

/**
 * 광고 생성 폼 (POST /api/ads).
 *
 * 폼은 URL 쿼리 `?videoFilename=…&originalName=…&title=…` 로 미리 채울 수
 * 있어, /videos 페이지에서 "광고 만들기"를 누르면 영상이 자동 선택된다.
 *
 * 검증 로직은 그대로, shadcn 프리미티브로 갈음. 시각·날짜 페어는 md:
 * 2컬럼, 모바일은 1컬럼.
 */

import { useCallback, useMemo, useState } from "react";
import Link from "next/link";
import { useSearchParams } from "next/navigation";

import { ApiError } from "@/lib/api";
import { shortId } from "@/lib/format";
import {
  DAILY_PLAY_COUNT_MAX,
  DAILY_PLAY_COUNT_MIN,
  type AdResponse,
  createAd,
} from "@/lib/ads";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";

type State =
  | { kind: "idle" }
  | { kind: "submitting" }
  | { kind: "success"; result: AdResponse }
  | {
      kind: "error";
      message: string;
      status: number | null;
      fieldErrors?: Record<string, string>;
    };

const HHMM = /^(?:[01]\d|2[0-3]):[0-5]\d$/;

export function CreateAdForm() {
  const searchParams = useSearchParams();

  const [title, setTitle] = useState(searchParams.get("title") ?? "");
  const [videoFilename, setVideoFilename] = useState(
    searchParams.get("videoFilename") ?? "",
  );
  const seedOriginalName = searchParams.get("originalName") ?? "";
  const [startTime, setStartTime] = useState("11:00");
  const [endTime, setEndTime] = useState("23:00");
  const [countStr, setCountStr] = useState("30");

  const today = new Date();
  const thirtyLater = new Date(today.getTime() + 30 * 24 * 60 * 60 * 1000);
  const fmt = (d: Date) =>
    `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
  const [campaignStartDate, setCampaignStartDate] = useState(fmt(today));
  const [campaignEndDate, setCampaignEndDate] = useState(fmt(thirtyLater));

  const [state, setState] = useState<State>({ kind: "idle" });
  const submitting = state.kind === "submitting";

  const dailyCount = useMemo(() => {
    const n = Number(countStr);
    return Number.isFinite(n) ? n : NaN;
  }, [countStr]);

  const handleSubmit = useCallback(
    async (e: React.FormEvent<HTMLFormElement>) => {
      e.preventDefault();
      if (submitting) return;

      const errs: Record<string, string> = {};
      if (!title.trim()) errs.title = "광고 제목을 입력해 주세요.";
      if (!videoFilename.trim())
        errs.videoFilename = "영상 파일명을 입력해 주세요.";
      if (!HHMM.test(startTime)) errs.startTime = "HH:mm 형식이어야 합니다.";
      if (!HHMM.test(endTime)) errs.endTime = "HH:mm 형식이어야 합니다.";
      if (!errs.startTime && !errs.endTime && endTime <= startTime) {
        errs.endTime = "종료 시간은 시작 시간 이후여야 합니다.";
      }
      if (
        !Number.isInteger(dailyCount) ||
        dailyCount < DAILY_PLAY_COUNT_MIN ||
        dailyCount > DAILY_PLAY_COUNT_MAX
      ) {
        errs.dailyPlayCount = `일일 송출 횟수는 ${DAILY_PLAY_COUNT_MIN}~${DAILY_PLAY_COUNT_MAX} 사이여야 합니다.`;
      }
      if (!campaignStartDate)
        errs.campaignStartDate = "시작일을 입력해 주세요.";
      if (!campaignEndDate) errs.campaignEndDate = "종료일을 입력해 주세요.";
      if (
        !errs.campaignStartDate &&
        !errs.campaignEndDate &&
        campaignEndDate < campaignStartDate
      ) {
        errs.campaignEndDate = "종료일은 시작일 이후여야 합니다.";
      }
      if (Object.keys(errs).length > 0) {
        setState({
          kind: "error",
          message: "입력값을 확인해 주세요.",
          status: null,
          fieldErrors: errs,
        });
        return;
      }

      setState({ kind: "submitting" });
      try {
        const result = await createAd({
          title: title.trim(),
          videoFilename: videoFilename.trim(),
          startTime,
          endTime,
          dailyPlayCount: dailyCount,
          campaignStartDate,
          campaignEndDate,
        });
        setState({ kind: "success", result });
      } catch (err) {
        if (err instanceof ApiError) {
          const body = err.body as
            | { message?: string; fieldErrors?: Record<string, string> }
            | null;
          setState({
            kind: "error",
            message:
              body?.message?.trim() || `요청 실패 (HTTP ${err.status})`,
            status: err.status,
            fieldErrors: body?.fieldErrors ?? undefined,
          });
        } else if (err instanceof Error) {
          setState({ kind: "error", message: err.message, status: null });
        } else {
          setState({
            kind: "error",
            message: "알 수 없는 오류",
            status: null,
          });
        }
      }
    },
    [
      title,
      videoFilename,
      startTime,
      endTime,
      dailyCount,
      campaignStartDate,
      campaignEndDate,
      submitting,
    ],
  );

  const fieldErrors = state.kind === "error" ? state.fieldErrors ?? {} : {};

  return (
    <Card>
      <CardHeader>
        <CardTitle>광고 만들기</CardTitle>
      </CardHeader>
      <CardContent>
        <form className="space-y-4" onSubmit={handleSubmit} noValidate>
          <fieldset className="space-y-4" disabled={submitting}>
            <Field id="ad-title" label="광고 제목" error={fieldErrors.title}>
              <Input
                id="ad-title"
                type="text"
                value={title}
                onChange={(e) => setTitle(e.target.value)}
                placeholder="예: 진로하이트 5월 캠페인"
                required
              />
            </Field>

            <Field
              id="ad-video-filename"
              label={
                <>
                  영상 파일명{" "}
                  {seedOriginalName && (
                    <span className="text-xs font-normal text-muted-foreground">
                      · 원본: {seedOriginalName}
                    </span>
                  )}
                </>
              }
              error={fieldErrors.videoFilename}
            >
              <Input
                id="ad-video-filename"
                type="text"
                value={videoFilename}
                onChange={(e) => setVideoFilename(e.target.value)}
                placeholder="62e970f5-...mp4 (어드민 영상 페이지에서 복사)"
                required
                spellCheck={false}
                autoComplete="off"
                className="font-mono"
              />
            </Field>

            <div className="grid gap-4 md:grid-cols-3">
              <Field
                id="ad-start"
                label="시작 시간"
                error={fieldErrors.startTime}
              >
                <Input
                  id="ad-start"
                  type="time"
                  required
                  step={60}
                  value={startTime}
                  onChange={(e) => setStartTime(e.target.value)}
                />
              </Field>
              <Field
                id="ad-end"
                label="종료 시간"
                error={fieldErrors.endTime}
              >
                <Input
                  id="ad-end"
                  type="time"
                  required
                  step={60}
                  value={endTime}
                  onChange={(e) => setEndTime(e.target.value)}
                />
              </Field>
              <Field
                id="ad-count"
                label={
                  <>
                    일일 송출 횟수{" "}
                    <span className="text-xs font-normal text-muted-foreground">
                      · {DAILY_PLAY_COUNT_MIN}~{DAILY_PLAY_COUNT_MAX}
                    </span>
                  </>
                }
                error={fieldErrors.dailyPlayCount}
              >
                <Input
                  id="ad-count"
                  type="number"
                  required
                  min={DAILY_PLAY_COUNT_MIN}
                  max={DAILY_PLAY_COUNT_MAX}
                  step={1}
                  value={countStr}
                  onChange={(e) => setCountStr(e.target.value)}
                />
              </Field>
            </div>

            <div>
              <h3 className="mt-2 text-xs font-semibold uppercase tracking-wider text-muted-foreground">
                캠페인 기간{" "}
                <span className="font-normal normal-case tracking-normal">
                  (이 기간이 지나면 자동으로 송출이 중단됩니다)
                </span>
              </h3>
              <div className="mt-2 grid gap-4 md:grid-cols-2">
                <Field
                  id="ad-camp-start"
                  label="시작일"
                  error={fieldErrors.campaignStartDate}
                >
                  <Input
                    id="ad-camp-start"
                    type="date"
                    required
                    value={campaignStartDate}
                    onChange={(e) => setCampaignStartDate(e.target.value)}
                  />
                </Field>
                <Field
                  id="ad-camp-end"
                  label="종료일"
                  error={fieldErrors.campaignEndDate}
                >
                  <Input
                    id="ad-camp-end"
                    type="date"
                    required
                    value={campaignEndDate}
                    onChange={(e) => setCampaignEndDate(e.target.value)}
                  />
                </Field>
              </div>
            </div>

            {state.kind === "error" && !state.fieldErrors && (
              <Alert variant="destructive">
                <AlertDescription>{state.message}</AlertDescription>
              </Alert>
            )}

            {state.kind === "success" && (
              <Alert variant="ok">
                <AlertDescription>
                  <strong>광고가 생성되었습니다.</strong>
                  <div className="mt-1.5">
                    광고 ID:{" "}
                    <code
                      className="select-all font-mono text-sm"
                      title={state.result.id}
                    >
                      {shortId(state.result.id)}
                    </code>
                  </div>
                  <div className="mt-1.5">
                    <Link
                      href={`/ads/${state.result.id}`}
                      className="text-emerald-300 underline-offset-4 hover:underline"
                    >
                      스케줄 편집 페이지로 이동 ↗
                    </Link>
                    {"  ·  "}
                    <Link
                      href="/ads"
                      className="text-emerald-300 underline-offset-4 hover:underline"
                    >
                      내 광고 목록
                    </Link>
                  </div>
                </AlertDescription>
              </Alert>
            )}

            <div className="flex flex-col gap-2 sm:flex-row sm:justify-end">
              <Button
                type="submit"
                disabled={submitting}
                aria-busy={submitting}
                className="w-full sm:w-auto"
              >
                {submitting ? "생성 중…" : "광고 생성"}
              </Button>
            </div>
          </fieldset>
        </form>
      </CardContent>
    </Card>
  );
}

interface FieldProps {
  id: string;
  label: React.ReactNode;
  error?: string;
  children: React.ReactNode;
}

function Field({ id, label, error, children }: FieldProps) {
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

export default CreateAdForm;
