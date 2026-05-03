"use client";

/**
 * 디바이스 모니터링 월 — 어드민이 한눈에 "지금 어떤 디바이스가 어떤 광고를
 * 송출 중인지" 볼 수 있도록 카드 그리드로 라이브 프리뷰를 깔아놓는다.
 *
 * 각 카드는:
 *   - 디바이스 이름 + 매핑된 음식점 라벨,
 *   - 큐에 담긴 광고 중 ACTIVE 인 것을 muted/loop/autoplay 로 라운드 로빈
 *     재생(현재 디바이스가 실제로 송출하는 모습과 거의 동일).
 *   - 큐가 비었거나 ACTIVE 가 없으면 splash placeholder.
 *
 * 주의:
 *   - 어디까지나 어드민용 미리보기. 실제 디바이스 동작(시간 윈도우, 일일
 *     횟수 제한)은 플레이어가 책임지므로 여기서는 단순히 "큐 안 ACTIVE
 *     광고를 돌려가며 보여준다" 만으로 충분.
 *   - 디바이스가 많을 때 동시에 수십 개 영상이 디코딩되면 무거우니
 *     `preload="metadata"` + `playsInline` + 작은 해상도로 부담을 낮춘다.
 */

import { useEffect, useMemo, useRef, useState } from "react";
import Link from "next/link";

import { apiUrl } from "@/lib/api";
import type { DeviceListItem, QueuedAdSummary } from "@/lib/devices";

interface Props {
  devices: DeviceListItem[];
  /** 현재 목록을 다시 fetch — "지금 새로고침" 버튼이 호출. */
  onRefresh?: () => void;
  refreshing?: boolean;
}

export function DeviceMonitorWall({ devices, onRefresh, refreshing }: Props) {
  if (devices.length === 0) return null;

  return (
    <section
      aria-label="디바이스 송출 모니터링"
      style={{ marginBottom: 24 }}
    >
      <div
        style={{
          display: "flex",
          alignItems: "baseline",
          justifyContent: "space-between",
          marginBottom: 8,
          gap: 12,
          flexWrap: "wrap",
        }}
      >
        <div>
          <h2 className="section-heading" style={{ margin: 0 }}>
            송출 모니터링
          </h2>
          <p className="muted" style={{ fontSize: 12, margin: "4px 0 0" }}>
            각 디바이스 큐에 담긴 ACTIVE 광고를 라운드 로빈으로 미리 재생합니다.
            실제 디바이스 송출과는 시간 윈도우 차이가 있을 수 있습니다.
          </p>
        </div>
        {onRefresh && (
          <button
            type="button"
            className="btn"
            onClick={onRefresh}
            disabled={refreshing}
          >
            {refreshing ? "새로고침 중…" : "↻ 지금 새로고침"}
          </button>
        )}
      </div>

      <div
        style={{
          display: "grid",
          gridTemplateColumns: "repeat(auto-fill, minmax(260px, 1fr))",
          gap: 12,
        }}
      >
        {devices.map((d) => (
          <DeviceMonitorCard key={d.deviceId} device={d} />
        ))}
      </div>
    </section>
  );
}

function DeviceMonitorCard({ device }: { device: DeviceListItem }) {
  const activeAds = useMemo(
    () => device.queuedAds.filter((q) => q.status === "ACTIVE"),
    [device.queuedAds],
  );
  const restaurantLabel = device.currentRestaurant?.restaurantName;

  return (
    <Link
      href={`/devices/${encodeURIComponent(device.deviceId)}`}
      style={{
        display: "block",
        background: "var(--bg-elev, #14171c)",
        border: "1px solid var(--border, #2a2f37)",
        borderRadius: 10,
        padding: 10,
        color: "inherit",
        textDecoration: "none",
        transition: "border-color 120ms ease",
      }}
    >
      <div
        style={{
          display: "flex",
          alignItems: "center",
          justifyContent: "space-between",
          gap: 8,
          marginBottom: 8,
        }}
      >
        <div style={{ minWidth: 0, flex: 1 }}>
          <div
            style={{
              fontWeight: 600,
              fontSize: 13,
              whiteSpace: "nowrap",
              overflow: "hidden",
              textOverflow: "ellipsis",
            }}
            title={device.deviceName}
          >
            {device.deviceName || "(이름 없음)"}
          </div>
          <div
            className="muted"
            style={{
              fontSize: 11,
              whiteSpace: "nowrap",
              overflow: "hidden",
              textOverflow: "ellipsis",
            }}
            title={restaurantLabel ?? "미할당"}
          >
            {restaurantLabel ? `📍 ${restaurantLabel}` : "📍 미할당"}
          </div>
        </div>
        <span
          className={
            activeAds.length > 0 ? "pill pill-ok" : "pill pill-muted"
          }
          style={{ flexShrink: 0 }}
        >
          {activeAds.length > 0 ? `송출 ${activeAds.length}` : "대기"}
        </span>
      </div>

      <PreviewStage ads={activeAds} />

      {device.queuedAds.length > activeAds.length && (
        <div
          className="muted"
          style={{ fontSize: 11, marginTop: 6, textAlign: "right" }}
        >
          큐 {device.queuedAds.length}개 중 {activeAds.length}개 송출 가능
        </div>
      )}
    </Link>
  );
}

/**
 * 활성 광고를 라운드 로빈으로 돌려가며 muted/loop 재생.
 * 단일 광고면 그대로 loop, 여러 개면 ended 이벤트로 다음 광고로 전환.
 */
function PreviewStage({ ads }: { ads: QueuedAdSummary[] }) {
  const [idx, setIdx] = useState(0);
  const videoRef = useRef<HTMLVideoElement | null>(null);

  // ads 가 바뀌면 idx 를 0 으로 리셋(예: 큐가 변경됨).
  useEffect(() => {
    setIdx(0);
  }, [ads.length, ads.map((a) => a.adId).join(",")]);

  // ads 가 0개이면 비디오 엘리먼트 자체를 안 띄우고 placeholder.
  if (ads.length === 0) {
    return <PreviewPlaceholder />;
  }

  const current = ads[idx % ads.length];
  const src = apiUrl(`/api/videos/${encodeURIComponent(current.videoFilename)}`);

  return (
    <div
      style={{
        position: "relative",
        background: "#000",
        borderRadius: 8,
        overflow: "hidden",
        aspectRatio: "16 / 9",
      }}
    >
      <video
        ref={videoRef}
        key={current.adId}
        src={src}
        muted
        autoPlay
        playsInline
        loop={ads.length === 1}
        preload="metadata"
        onEnded={() => {
          if (ads.length > 1) setIdx((i) => (i + 1) % ads.length);
        }}
        onError={() => {
          // 한 영상 로드가 실패해도 다음으로 넘어가서 다른 디바이스 모니터를 막지 않게.
          if (ads.length > 1) setIdx((i) => (i + 1) % ads.length);
        }}
        style={{
          width: "100%",
          height: "100%",
          objectFit: "cover",
          display: "block",
        }}
      />
      <div
        style={{
          position: "absolute",
          inset: "auto 0 0 0",
          padding: "6px 8px",
          background:
            "linear-gradient(transparent, rgba(0,0,0,0.7))",
          color: "#fff",
          fontSize: 11,
          display: "flex",
          alignItems: "center",
          justifyContent: "space-between",
          gap: 6,
        }}
      >
        <span
          style={{
            whiteSpace: "nowrap",
            overflow: "hidden",
            textOverflow: "ellipsis",
            minWidth: 0,
          }}
          title={current.title}
        >
          ▶ {current.title || current.videoFilename}
        </span>
        {ads.length > 1 && (
          <span style={{ flexShrink: 0, opacity: 0.85 }}>
            {(idx % ads.length) + 1} / {ads.length}
          </span>
        )}
      </div>
    </div>
  );
}

function PreviewPlaceholder() {
  return (
    <div
      style={{
        background:
          "linear-gradient(135deg, rgba(245,176,66,0.08), rgba(245,176,66,0.02))",
        border: "1px dashed var(--border, #2a2f37)",
        borderRadius: 8,
        aspectRatio: "16 / 9",
        display: "flex",
        alignItems: "center",
        justifyContent: "center",
        color: "var(--muted, #9aa1a9)",
        fontSize: 12,
        textAlign: "center",
        padding: 8,
      }}
    >
      큐에 ACTIVE 광고가 없습니다
      <br />
      <span style={{ fontSize: 11, opacity: 0.8 }}>
        디바이스 상세에서 광고를 큐에 담아주세요
      </span>
    </div>
  );
}

export default DeviceMonitorWall;
