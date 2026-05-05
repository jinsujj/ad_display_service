"use client";

/**
 * 광고 생성 폼 (POST /api/ads).
 *
 * 광고주는 자기 영상 목록에서 골라 담는다 — 내부 저장 파일명(UUID) 은 절대
 * 노출되지 않고, 운영자가 선택한 영상의 friendly originalName 만 보인다.
 * 폼은 URL 쿼리 `?videoFilename=…&title=…` 로 미리 채울 수 있어 /videos
 * 페이지의 "광고 만들기" 버튼에서 영상이 자동 선택된다.
 */

import { useCallback, useEffect, useMemo, useState } from "react";
import Link from "next/link";
import { useSearchParams } from "next/navigation";

import { ApiError } from "@/lib/api";
import {
  DAILY_PLAY_COUNT_MAX,
  DAILY_PLAY_COUNT_MIN,
  type AdResponse,
  createAd,
} from "@/lib/ads";
import { formatBytes, listVideos, type VideoListItem } from "@/lib/videos";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";

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
  const seedVideoFilename = searchParams.get("videoFilename") ?? "";

  const [title, setTitle] = useState(searchParams.get("title") ?? "");
  // videoFilename 은 사용자에게 보이지 않는 internal id. Select 의 value 로만
  // 관리되며 어떤 텍스트 input 으로도 노출하지 않는다.
  const [videoFilename, setVideoFilename] = useState(seedVideoFilename);
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

  // 영상 선택 드롭다운용 — 마운트 시 목록 fetch.
  type VideosState =
    | { kind: "loading" }
    | { kind: "ready"; videos: VideoListItem[] }
    | { kind: "error"; message: string };
  const [videos, setVideos] = useState<VideosState>({ kind: "loading" });

  useEffect(() => {
    let cancelled = false;
    listVideos()
      .then((rows) => {
        if (!cancelled) setVideos({ kind: "ready", videos: rows });
      })
      .catch((err) => {
        if (cancelled) return;
        const msg =
          err instanceof ApiError
            ? `HTTP ${err.status}`
            : err instanceof Error
              ? err.message
              : "알 수 없는 오류";
        setVideos({ kind: "error", message: msg });
      });
    return () => {
      cancelled = true;
    };
  }, []);

  // 쿼리 파라미터로 미리 정해진 videoFilename 이 있으면 영상 목록 도착 시
  // 매칭되는 항목으로 잠금. 매칭 실패하면 사용자에게 직접 고르도록 빈 상태.
  useEffect(() => {
    if (videos.kind !== "ready") return;
    if (!videoFilename) return;
    const match = videos.videos.find((v) => v.filename === videoFilename);
    if (!match) {
      setVideoFilename("");
    }
  }, [videos, videoFilename]);

  // 제목 자동 채움 — 쿼리에 title 이 없는데 영상이 처음 선택되는 순간
  // 그 영상의 originalName(확장자 제외) 으로 기본 제목 제안.
  useEffect(() => {
    if (title) return;
    if (videos.kind !== "ready") return;
    if (!videoFilename) return;
    const v = videos.videos.find((x) => x.filename === videoFilename);
    if (v?.originalName) {
      setTitle(v.originalName.replace(/\.[^.]+$/, ""));
    }
  }, [videos, videoFilename, title]);

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
      if (!videoFilename) errs.videoFilename = "광고 영상을 선택해 주세요.";
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
          videoFilename,
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
            {/* 영상 선택 — 사용자에겐 originalName 만 보임. */}
            <div className="space-y-1.5">
              <Label htmlFor="ad-video-select">광고 영상</Label>
              {videos.kind === "loading" && (
                <div className="text-sm text-muted-foreground">
                  영상 목록을 불러오는 중…
                </div>
              )}
              {videos.kind === "error" && (
                <Alert variant="destructive">
                  <AlertDescription>
                    영상 목록을 불러오지 못했습니다: {videos.message}
                  </AlertDescription>
                </Alert>
              )}
              {videos.kind === "ready" && videos.videos.length === 0 && (
                <Alert>
                  <AlertDescription>
                    아직 업로드한 영상이 없습니다.{" "}
                    <Link
                      href="/videos"
                      className="text-accent underline-offset-4 hover:underline"
                    >
                      영상 페이지
                    </Link>
                    에서 MP4 를 먼저 올려 주세요.
                  </AlertDescription>
                </Alert>
              )}
              {videos.kind === "ready" && videos.videos.length > 0 && (
                <Select
                  value={videoFilename}
                  onValueChange={(v) => setVideoFilename(v)}
                >
                  <SelectTrigger
                    id="ad-video-select"
                    aria-invalid={
                      Boolean(fieldErrors.videoFilename) || undefined
                    }
                  >
                    <SelectValue placeholder="업로드한 영상 중 하나를 선택하세요" />
                  </SelectTrigger>
                  <SelectContent>
                    {videos.videos.map((v) => (
                      <SelectItem
                        key={v.filename}
                        value={v.filename}
                      >
                        <div className="flex flex-col">
                          <span className="font-medium">
                            {v.originalName || "이름 없음"}
                          </span>
                          <span className="text-xs text-muted-foreground">
                            {formatBytes(v.sizeBytes)}
                          </span>
                        </div>
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              )}
              {fieldErrors.videoFilename && (
                <p className="text-xs text-destructive" role="alert">
                  {fieldErrors.videoFilename}
                </p>
              )}
            </div>

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
                disabled={
                  submitting ||
                  videos.kind === "loading" ||
                  (videos.kind === "ready" && videos.videos.length === 0)
                }
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
