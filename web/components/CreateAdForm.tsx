"use client";

/**
 * 광고 생성 폼 (POST /api/ads).
 *
 * 입력:
 *   - 제목 (필수)
 *   - 영상 파일명 (필수, /videos 페이지의 영상 행에서 받은 filename)
 *   - 시작/종료 시간, 일일 송출 횟수 (스케줄 폼과 동일 검증)
 *
 * 성공 시:
 *   - 응답으로 받은 광고 ID를 큰 글씨로 노출 (사용자가 바로 복사 가능)
 *   - "스케줄 편집" 링크로 /ads/{id} 이동
 *
 * 폼은 URL 쿼리 `?videoFilename=…&originalName=…&title=…` 로 미리 채울 수
 * 있어, /videos 페이지에서 "광고 만들기"를 누르면 영상이 자동 선택된 채로
 * 운영자가 제목/스케줄만 입력하면 된다.
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

  // 캠페인 기간 기본값: 오늘 ~ 30일 후
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
      if (!videoFilename.trim()) errs.videoFilename = "영상 파일명을 입력해 주세요.";
      if (!HHMM.test(startTime)) errs.startTime = "HH:mm 형식이어야 합니다.";
      if (!HHMM.test(endTime)) errs.endTime = "HH:mm 형식이어야 합니다.";
      if (!errs.startTime && !errs.endTime && endTime <= startTime) {
        errs.endTime = "종료 시간은 시작 시간 이후여야 합니다.";
      }
      if (!Number.isInteger(dailyCount) || dailyCount < DAILY_PLAY_COUNT_MIN || dailyCount > DAILY_PLAY_COUNT_MAX) {
        errs.dailyPlayCount = `일일 송출 횟수는 ${DAILY_PLAY_COUNT_MIN}~${DAILY_PLAY_COUNT_MAX} 사이여야 합니다.`;
      }
      if (!campaignStartDate) errs.campaignStartDate = "시작일을 입력해 주세요.";
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
            message: body?.message?.trim() || `요청 실패 (HTTP ${err.status})`,
            status: err.status,
            fieldErrors: body?.fieldErrors ?? undefined,
          });
        } else if (err instanceof Error) {
          setState({ kind: "error", message: err.message, status: null });
        } else {
          setState({ kind: "error", message: "알 수 없는 오류", status: null });
        }
      }
    },
    [title, videoFilename, startTime, endTime, dailyCount, campaignStartDate, campaignEndDate, submitting],
  );

  const fieldErrors = state.kind === "error" ? state.fieldErrors ?? {} : {};

  return (
    <form className="schedule-form" onSubmit={handleSubmit} noValidate>
      <fieldset className="schedule-form__fieldset" disabled={submitting}>
        <legend className="schedule-form__legend">광고 만들기</legend>

        <div className="schedule-form__field">
          <label htmlFor="ad-title" className="schedule-form__label">광고 제목</label>
          <input
            id="ad-title"
            className="schedule-form__input"
            type="text"
            value={title}
            onChange={(e) => setTitle(e.target.value)}
            placeholder="예: 진로하이트 5월 캠페인"
            required
          />
          {fieldErrors.title && (
            <div className="schedule-form__field-error" role="alert">{fieldErrors.title}</div>
          )}
        </div>

        <div className="schedule-form__field">
          <label htmlFor="ad-video-filename" className="schedule-form__label">
            영상 파일명{" "}
            {seedOriginalName && (
              <span className="muted">· 원본: {seedOriginalName}</span>
            )}
          </label>
          <input
            id="ad-video-filename"
            className="schedule-form__input"
            type="text"
            value={videoFilename}
            onChange={(e) => setVideoFilename(e.target.value)}
            placeholder="62e970f5-...mp4 (어드민 영상 페이지에서 복사)"
            required
            spellCheck={false}
            autoComplete="off"
          />
          {fieldErrors.videoFilename && (
            <div className="schedule-form__field-error" role="alert">{fieldErrors.videoFilename}</div>
          )}
        </div>

        <div className="schedule-form__grid">
          <div className="schedule-form__field">
            <label htmlFor="ad-start" className="schedule-form__label">시작 시간</label>
            <input
              id="ad-start"
              type="time"
              required
              step={60}
              value={startTime}
              onChange={(e) => setStartTime(e.target.value)}
              className="schedule-form__input"
            />
            {fieldErrors.startTime && (
              <div className="schedule-form__field-error" role="alert">{fieldErrors.startTime}</div>
            )}
          </div>

          <div className="schedule-form__field">
            <label htmlFor="ad-end" className="schedule-form__label">종료 시간</label>
            <input
              id="ad-end"
              type="time"
              required
              step={60}
              value={endTime}
              onChange={(e) => setEndTime(e.target.value)}
              className="schedule-form__input"
            />
            {fieldErrors.endTime && (
              <div className="schedule-form__field-error" role="alert">{fieldErrors.endTime}</div>
            )}
          </div>

          <div className="schedule-form__field">
            <label htmlFor="ad-count" className="schedule-form__label">
              일일 송출 횟수
              <span className="muted"> · {DAILY_PLAY_COUNT_MIN}~{DAILY_PLAY_COUNT_MAX}</span>
            </label>
            <input
              id="ad-count"
              type="number"
              required
              min={DAILY_PLAY_COUNT_MIN}
              max={DAILY_PLAY_COUNT_MAX}
              step={1}
              value={countStr}
              onChange={(e) => setCountStr(e.target.value)}
              className="schedule-form__input"
            />
            {fieldErrors.dailyPlayCount && (
              <div className="schedule-form__field-error" role="alert">{fieldErrors.dailyPlayCount}</div>
            )}
          </div>
        </div>

        <h3 className="section-heading" style={{ marginTop: 16, fontSize: 14 }}>
          캠페인 기간 (이 기간이 지나면 자동으로 송출이 중단됩니다)
        </h3>
        <div className="schedule-form__grid">
          <div className="schedule-form__field">
            <label htmlFor="ad-camp-start" className="schedule-form__label">시작일</label>
            <input
              id="ad-camp-start"
              type="date"
              required
              value={campaignStartDate}
              onChange={(e) => setCampaignStartDate(e.target.value)}
              className="schedule-form__input"
            />
            {fieldErrors.campaignStartDate && (
              <div className="schedule-form__field-error" role="alert">{fieldErrors.campaignStartDate}</div>
            )}
          </div>
          <div className="schedule-form__field">
            <label htmlFor="ad-camp-end" className="schedule-form__label">종료일</label>
            <input
              id="ad-camp-end"
              type="date"
              required
              value={campaignEndDate}
              onChange={(e) => setCampaignEndDate(e.target.value)}
              className="schedule-form__input"
            />
            {fieldErrors.campaignEndDate && (
              <div className="schedule-form__field-error" role="alert">{fieldErrors.campaignEndDate}</div>
            )}
          </div>
        </div>

        {state.kind === "error" && !state.fieldErrors && (
          <div className="notice notice-error" role="alert">{state.message}</div>
        )}

        {state.kind === "success" && (
          <div
            className="notice"
            role="status"
            style={{
              borderColor: "rgba(74, 222, 128, 0.5)",
              background: "rgba(74, 222, 128, 0.08)",
              color: "var(--ok)",
            }}
          >
            <strong>광고가 생성되었습니다.</strong>
            <div style={{ marginTop: 6 }}>
              광고 ID:{" "}
              <code
                style={{ fontSize: 13, userSelect: "all" }}
                title={state.result.id}
              >
                {shortId(state.result.id)}
              </code>
            </div>
            <div style={{ marginTop: 6 }}>
              <Link href={`/ads/${state.result.id}`}>스케줄 편집 페이지로 이동 ↗</Link>
              {"  ·  "}
              <Link href="/ads">내 광고 목록</Link>
            </div>
          </div>
        )}

        <div className="toolbar schedule-form__actions">
          <button type="submit" className="btn" aria-busy={submitting} disabled={submitting}>
            {submitting ? "생성 중…" : "광고 생성"}
          </button>
        </div>
      </fieldset>
    </form>
  );
}

export default CreateAdForm;
