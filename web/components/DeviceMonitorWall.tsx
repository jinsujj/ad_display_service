"use client";

/**
 * 디바이스 모니터링 월 — 어드민이 한눈에 "지금 어떤 디바이스가 어떤 광고를
 * 송출 중인지" 보는 카드 그리드.
 *
 * **이 컴포넌트는 시뮬레이션을 하지 않는다.** 카드의 영상은 서버가 가장 최근
 * STARTED play-event 로 결정한 *디바이스의 실제 송출 광고* 만 미러링한다.
 * 디바이스 앱이 종료된 카드는 "오프라인" 으로 회색 처리되어 죽은 디바이스를
 * 즉시 식별 가능.
 *
 * 카드 상태 분기:
 *   - LIVE       : online && currentAd → 그 광고를 muted/autoplay/loop 재생
 *   - 송출 대기  : online && !currentAd → placeholder ("큐 비었거나 시간 윈도우 밖")
 *   - 오프라인   : !online → 회색 카드, "🔌 오프라인 · 마지막 활동 N분 전"
 *
 * 폴링 주기는 상위(`MyDevicesList`) 의 AUTO_REFRESH_MS 가 결정한다.
 */

import { useCallback, useMemo } from "react";
import Link from "next/link";

import { apiUrl } from "@/lib/api";
import type { CurrentAd, DeviceListItem } from "@/lib/devices";

interface Props {
  devices: DeviceListItem[];
  /** 현재 목록을 다시 fetch — "지금 새로고침" 버튼이 호출. */
  onRefresh?: () => void;
  refreshing?: boolean;
}

export function DeviceMonitorWall({ devices, onRefresh, refreshing }: Props) {
  if (devices.length === 0) return null;

  const liveCount = devices.filter((d) => d.online && d.currentAd).length;
  const onlineCount = devices.filter((d) => d.online).length;
  const offlineCount = devices.length - onlineCount;

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
            서버에 도달한 가장 최근 STARTED 이벤트 기준 — 디바이스가 실제로
            지금 송출 중인 광고만 표시합니다. 앱이 종료된 디바이스는 자동으로
            오프라인 처리됩니다.
          </p>
        </div>
        <div
          style={{
            display: "flex",
            gap: 8,
            alignItems: "center",
            flexWrap: "wrap",
          }}
        >
          <span className="pill pill-ok">🔴 LIVE {liveCount}</span>
          {offlineCount > 0 && (
            <span className="pill pill-muted">🔌 오프라인 {offlineCount}</span>
          )}
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
  const restaurantLabel = device.currentRestaurant?.restaurantName;
  const offline = !device.online;

  return (
    <Link
      href={`/devices/${encodeURIComponent(device.deviceId)}`}
      style={{
        display: "block",
        background: "var(--bg-elev, #14171c)",
        border: offline
          ? "1px solid rgba(255,255,255,0.06)"
          : device.currentAd
            ? "1px solid rgba(74, 222, 128, 0.35)"
            : "1px solid var(--border, #2a2f37)",
        borderRadius: 10,
        padding: 10,
        color: "inherit",
        textDecoration: "none",
        opacity: offline ? 0.55 : 1,
        transition: "border-color 120ms ease, opacity 120ms ease",
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
              display: "flex",
              alignItems: "center",
              gap: 6,
            }}
            title={device.deviceName}
          >
            <span
              aria-hidden="true"
              style={{
                width: 8,
                height: 8,
                borderRadius: "50%",
                flexShrink: 0,
                background: offline
                  ? "#6b7280"
                  : device.currentAd
                    ? "#22c55e"
                    : "#f59e0b",
                boxShadow:
                  !offline && device.currentAd
                    ? "0 0 6px rgba(34, 197, 94, 0.7)"
                    : undefined,
              }}
            />
            <span
              style={{
                whiteSpace: "nowrap",
                overflow: "hidden",
                textOverflow: "ellipsis",
              }}
            >
              {device.deviceName || "(이름 없음)"}
            </span>
          </div>
          <div
            className="muted"
            style={{
              fontSize: 11,
              whiteSpace: "nowrap",
              overflow: "hidden",
              textOverflow: "ellipsis",
              marginTop: 2,
            }}
            title={restaurantLabel ?? "미할당"}
          >
            {restaurantLabel ? `📍 ${restaurantLabel}` : "📍 미할당"}
          </div>
        </div>
        <CardBadge device={device} />
      </div>

      {offline ? (
        <OfflinePane lastSeenAt={device.lastSeenAt} />
      ) : device.currentAd ? (
        <LivePane currentAd={device.currentAd} />
      ) : (
        <IdlePane queuedCount={device.queuedAds.length} />
      )}
    </Link>
  );
}

function CardBadge({ device }: { device: DeviceListItem }) {
  if (!device.online) {
    return (
      <span className="pill pill-muted" style={{ flexShrink: 0 }}>
        오프라인
      </span>
    );
  }
  if (device.currentAd) {
    return (
      <span className="pill pill-ok" style={{ flexShrink: 0 }}>
        🔴 LIVE
      </span>
    );
  }
  return (
    <span className="pill" style={{ flexShrink: 0 }}>
      대기
    </span>
  );
}

function LivePane({ currentAd }: { currentAd: CurrentAd }) {
  // adId+startedAt 을 key 로 잡으면 디바이스가 다음 광고로 넘어갔을 때
  // <video> 가 강제 remount 되어 새 src 로 깨끗하게 재시작.
  const videoKey = `${currentAd.adId}|${currentAd.startedAt}`;
  const src = useMemo(
    () => apiUrl(`/api/videos/${encodeURIComponent(currentAd.videoFilename)}`),
    [currentAd.videoFilename],
  );

  // 디바이스가 광고 N초째 재생 중일 때, 모니터 영상을 *같은 N초 위치* 로
  // 점프시켜 시각적 동기를 맞춘다. 이 시킹이 없으면 모니터는 항상 0초부터
  // 시작해 운영자 눈에 "디바이스랑 어긋나 보이는" 딜레이로 보인다.
  //
  // 시킹 정책:
  //   - <video> 의 metadata 가 로드되면(`onLoadedMetadata`) duration 사용 가능.
  //   - 디바이스 STARTED 이벤트가 광고 N번째 loop 인지 모르므로, 경과 시간을
  //     duration 으로 modulo 해서 현재 loop 의 진행 위치를 계산.
  //   - duration 미상(라이브 스트림 등) 이면 시킹 생략.
  //   - currentTime > duration 이면 브라우저가 reject 할 수 있어 가드 필수.
  const handleLoadedMetadata = useCallback(
    (e: React.SyntheticEvent<HTMLVideoElement>) => {
      const video = e.currentTarget;
      const duration = video.duration;
      if (!Number.isFinite(duration) || duration <= 0) return;
      try {
        const startedMs = new Date(currentAd.startedAt).getTime();
        if (Number.isNaN(startedMs)) return;
        const elapsedSec = (Date.now() - startedMs) / 1000;
        if (elapsedSec < 0) return;
        // 광고가 loop=true 라 디바이스도 끝나면 처음부터 → modulo 로 현재 loop 위치.
        const seekTo = elapsedSec % duration;
        // duration 에 너무 가까이 가면 브라우저가 자동 loop 트리거하므로 살짝 안쪽.
        video.currentTime = Math.min(seekTo, Math.max(0, duration - 0.1));
      } catch {
        // seek 실패는 시각적 딜레이로 보일 뿐, 재생 자체는 계속됨 — swallow.
      }
    },
    [currentAd.startedAt],
  );

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
        key={videoKey}
        src={src}
        muted
        autoPlay
        playsInline
        loop
        preload="auto"
        onLoadedMetadata={handleLoadedMetadata}
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
          background: "linear-gradient(transparent, rgba(0,0,0,0.7))",
          color: "#fff",
          fontSize: 11,
          display: "flex",
          alignItems: "center",
          gap: 6,
          minWidth: 0,
        }}
      >
        <span
          aria-hidden="true"
          style={{
            color: "#ef4444",
            flexShrink: 0,
          }}
        >
          ●
        </span>
        <span
          style={{
            whiteSpace: "nowrap",
            overflow: "hidden",
            textOverflow: "ellipsis",
            minWidth: 0,
            flex: 1,
          }}
          title={currentAd.title}
        >
          {currentAd.title || currentAd.videoFilename}
        </span>
      </div>
    </div>
  );
}

function IdlePane({ queuedCount }: { queuedCount: number }) {
  return (
    <div
      style={{
        background:
          "linear-gradient(135deg, rgba(245,176,66,0.08), rgba(245,176,66,0.02))",
        border: "1px dashed var(--border, #2a2f37)",
        borderRadius: 8,
        aspectRatio: "16 / 9",
        display: "flex",
        flexDirection: "column",
        alignItems: "center",
        justifyContent: "center",
        color: "var(--muted, #9aa1a9)",
        fontSize: 12,
        textAlign: "center",
        padding: 8,
        gap: 4,
      }}
    >
      <div>송출 대기</div>
      <div style={{ fontSize: 11, opacity: 0.8 }}>
        {queuedCount > 0
          ? "큐는 있지만 시간 윈도우 밖이거나 일일 한도 도달"
          : "큐가 비었습니다 — 디바이스 상세에서 광고를 담아주세요"}
      </div>
    </div>
  );
}

function OfflinePane({ lastSeenAt }: { lastSeenAt: string | null }) {
  const ago = lastSeenAt ? formatTimeAgo(lastSeenAt) : null;
  return (
    <div
      style={{
        background: "rgba(255,255,255,0.02)",
        border: "1px dashed rgba(255,255,255,0.08)",
        borderRadius: 8,
        aspectRatio: "16 / 9",
        display: "flex",
        flexDirection: "column",
        alignItems: "center",
        justifyContent: "center",
        color: "var(--muted, #9aa1a9)",
        fontSize: 12,
        textAlign: "center",
        padding: 8,
        gap: 4,
      }}
    >
      <div style={{ fontSize: 18 }}>🔌</div>
      <div>오프라인</div>
      {ago && (
        <div style={{ fontSize: 11, opacity: 0.8 }}>마지막 활동 {ago}</div>
      )}
    </div>
  );
}

function formatTimeAgo(iso: string): string {
  try {
    const then = new Date(iso).getTime();
    if (Number.isNaN(then)) return "알 수 없음";
    const sec = Math.max(0, Math.floor((Date.now() - then) / 1000));
    if (sec < 60) return `${sec}초 전`;
    const min = Math.floor(sec / 60);
    if (min < 60) return `${min}분 전`;
    const hr = Math.floor(min / 60);
    if (hr < 24) return `${hr}시간 전`;
    const day = Math.floor(hr / 24);
    return `${day}일 전`;
  } catch {
    return "알 수 없음";
  }
}

export default DeviceMonitorWall;
