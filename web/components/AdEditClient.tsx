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

import { useCallback, useEffect, useState } from "react";
import Link from "next/link";

import { ApiError } from "@/lib/api";
import {
  AD_STATUS_LABEL,
  getAd,
  getAdDeployments,
  type AdDeploymentItem,
  type AdResponse,
} from "@/lib/ads";
import { useDataChanged } from "@/lib/dataEvents";
import { shortId } from "@/lib/format";
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
          <strong>{ad.title}</strong>{" "}
          <span
            className={`pill pill-${ad.status === "ACTIVE" ? "ok" : ad.status === "EXPIRED" ? "warn" : ""}`}
            style={{ marginLeft: 6 }}
          >
            {AD_STATUS_LABEL[ad.status]}
          </span>
          <div className="muted" style={{ fontSize: 12, marginTop: 4 }}>
            광고 ID <code className="ad-id-banner__id" title={ad.id}>{shortId(ad.id)}</code>
          </div>
          <div className="muted" style={{ fontSize: 12 }}>
            영상 파일명 <code title={ad.videoFilename}>{shortId(ad.videoFilename)}</code>
          </div>
          <div className="muted" style={{ fontSize: 12 }}>
            현재 스케줄: {ad.startTime} ~ {ad.endTime} · 일일 {ad.dailyPlayCount}회
          </div>
          <div className="muted" style={{ fontSize: 12 }}>
            캠페인 기간: {ad.campaignStartDate} ~ {ad.campaignEndDate}
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
          campaignStartDate: ad.campaignStartDate,
          campaignEndDate: ad.campaignEndDate,
        }}
      />

      <AdDeploymentsSection adId={ad.id} />
    </>
  );
}

/* --------------------------- 송출 현황 (read-only, 광고주용) ----------------- */

function AdDeploymentsSection({ adId }: { adId: string }) {
  const [deployments, setDeployments] = useState<AdDeploymentItem[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  const refetch = useCallback(() => {
    getAdDeployments(adId)
      .then((rows) => {
        setDeployments(rows);
        setError(null);
      })
      .catch((err) => {
        const msg =
          err instanceof ApiError
            ? `HTTP ${err.status}`
            : err instanceof Error
              ? err.message
              : "알 수 없는 오류";
        setError(msg);
      });
  }, [adId]);

  useEffect(() => {
    refetch();
  }, [refetch]);

  // 큐 변경 / 광고 변경 시 자동 갱신.
  useDataChanged(["device-queue", "ad"], refetch);

  return (
    <>
      <h2 className="section-heading" style={{ marginTop: 28 }}>
        송출 현황
      </h2>
      <p className="muted" style={{ fontSize: 12, marginTop: 0, marginBottom: 12 }}>
        이 광고가 깔린 디바이스 — 운영자(OPERATOR) 가 큐에 담았을 때 여기 표시됩니다.
        디바이스 매칭은 플랫폼 운영자가 통제합니다.
      </p>

      {error ? (
        <div className="notice notice-error" role="alert">
          송출 현황을 불러오지 못했습니다: {error}
        </div>
      ) : deployments === null ? (
        <div className="muted">송출 현황 불러오는 중…</div>
      ) : deployments.length === 0 ? (
        <div className="empty-state">
          이 광고는 아직 어떤 디바이스에도 깔려 있지 않습니다. 운영자에게
          매칭을 요청하세요.
        </div>
      ) : (
        <table className="data-table" aria-label="송출 디바이스">
          <colgroup>
            <col style={{ width: 92 }} />
            <col />
            <col />
            <col style={{ width: 200 }} />
          </colgroup>
          <thead>
            <tr>
              <th>상태</th>
              <th>디바이스</th>
              <th>음식점</th>
              <th>큐 등록일</th>
            </tr>
          </thead>
          <tbody>
            {deployments.map((d) => (
              <tr key={d.deviceId}>
                <td>
                  {d.currentlyPlaying ? (
                    <span className="pill pill-ok">🔴 LIVE</span>
                  ) : (
                    <span className="pill pill-muted">대기</span>
                  )}
                </td>
                <td>
                  <strong>{d.deviceName}</strong>
                </td>
                <td className="muted">{d.restaurantName ?? "(미할당)"}</td>
                <td className="muted" style={{ fontSize: 12, whiteSpace: "nowrap" }}>
                  {new Date(d.addedAt).toLocaleString("ko-KR")}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </>
  );
}

export default AdEditClient;
