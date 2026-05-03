"use client";

/**
 * /ads/[id] 페이지의 클라이언트 호스트.
 *
 * - 마운트 시 GET /api/ads/{id} 로 단일 광고를 받아오고
 *   AdScheduleForm 의 initialValues 로 주입한다 (시작 시간 / 종료 시간 /
 *   일일 송출 횟수 미리 채움).
 * - 광고 메타(제목, 영상 파일명, 생성 시각)도 보여줘 운영자가 어떤 광고를
 *   편집 중인지 즉시 알 수 있다.
 * - 404 (소유 광고 아님 / 존재하지 않음) 와 그 외 오류를 분리해 노출.
 */

import { useEffect, useState } from "react";
import Link from "next/link";

import { ApiError } from "@/lib/api";
import { getAd, type AdResponse } from "@/lib/ads";
import { AdScheduleForm } from "./AdScheduleForm";

type State =
  | { kind: "loading" }
  | { kind: "ready"; ad: AdResponse }
  | { kind: "not-found" }
  | { kind: "error"; message: string };

interface Props {
  adId: string;
}

export function AdEditClient({ adId }: Props) {
  const [state, setState] = useState<State>({ kind: "loading" });

  useEffect(() => {
    let cancelled = false;
    setState({ kind: "loading" });
    getAd(adId)
      .then((ad) => {
        if (!cancelled) setState({ kind: "ready", ad });
      })
      .catch((err) => {
        if (cancelled) return;
        if (err instanceof ApiError && err.status === 404) {
          setState({ kind: "not-found" });
          return;
        }
        const msg =
          err instanceof ApiError
            ? `HTTP ${err.status}`
            : err instanceof Error
              ? err.message
              : "알 수 없는 오류";
        setState({ kind: "error", message: msg });
      });
    return () => {
      cancelled = true;
    };
  }, [adId]);

  if (state.kind === "loading") {
    return <div className="muted">광고 정보를 불러오는 중…</div>;
  }

  if (state.kind === "not-found") {
    return (
      <div className="notice notice-error" role="alert">
        해당 광고를 찾을 수 없습니다 (또는 본인 광고가 아닙니다).{" "}
        <Link href="/ads">광고 목록으로 돌아가기</Link>
      </div>
    );
  }

  if (state.kind === "error") {
    return (
      <div className="notice notice-error" role="alert">
        광고 정보를 불러오지 못했습니다: {state.message}
      </div>
    );
  }

  const ad = state.ad;
  return (
    <>
      <div className="ad-id-banner" style={{ marginBottom: 12 }}>
        <div>
          <strong>{ad.title}</strong>
          <div className="muted" style={{ fontSize: 12, marginTop: 4 }}>
            광고 ID <code className="ad-id-banner__id">{ad.id}</code>
          </div>
          <div className="muted" style={{ fontSize: 12 }}>
            영상 파일명 <code>{ad.videoFilename}</code>
          </div>
          <div className="muted" style={{ fontSize: 12 }}>
            현재 스케줄: {ad.startTime} ~ {ad.endTime} · 일일 {ad.dailyPlayCount}회
          </div>
        </div>
      </div>

      <h2 className="section-heading">스케줄</h2>
      <AdScheduleForm
        adId={ad.id}
        initialValues={{
          startTime: ad.startTime,
          endTime: ad.endTime,
          dailyPlayCount: ad.dailyPlayCount,
        }}
      />
    </>
  );
}

export default AdEditClient;
