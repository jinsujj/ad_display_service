/**
 * 플레이어 페이지 라우트 셸 — `/player/{deviceId}`.
 *
 * AC 5, Sub-AC 4 (Player/Android):
 *   "Implement SSE subscription on player page so device reloads schedule
 *    immediately when remapped without manual refresh."
 *
 * 안드로이드 WebView 래퍼가 부팅 시 이 라우트를 연다(참고:
 * `android/app/src/main/java/com/owldev/adsignage/MainActivity.kt`,
 * `buildPlayerUrl(deviceId)`), 따라서 전체 재생 런타임이 안드로이드
 * 디바이스의 브라우저에 산다 — 이 sub-AC가 제공하는 SSE 구독 포함.
 *
 * 이 페이지는 의도적으로 얇음:
 *   - 서버 컴포넌트 셸(이 파일): 라우트 params에서 deviceId를 꺼내고,
 *     아래 클라이언트 컴포넌트를 렌더하며, 플레이어 친화 viewport / 페이지
 *     메타데이터를 설정.
 *   - 클라이언트 컴포넌트(`PlayerClient`): `usePlayerSse`를 통한 SSE 구독과
 *     모든 재할당 이벤트마다 플레이리스트 재 fetch를 소유.
 *
 * 형제 sub-AC들(스케줄 윈도우 내 라운드 로빈 재생, 스케줄 외 스플래시
 * 화면, 영상 range fetch)은 `PlayerClient`가 노출하는 `playlist` 상태
 * 위에 작성된다 — 그것들은 SSE의 존재를 알 필요가 없다.
 */

import type { Metadata } from "next";
import { PlayerClient } from "./PlayerClient";

// 플레이어는 항상 라이브 디바이스 → 음식점 매핑을 반영해야 한다;
// 여기서 캐싱하면 다음 내비게이션까지 재할당이 숨겨진다. 데이터 캐시와
// 풀 페이지 정적 렌더링 둘 다 비활성.
export const dynamic = "force-dynamic";
export const revalidate = 0;

export const metadata: Metadata = {
  title: "AdSignage Player",
  description:
    "Restaurant fridge digital signage player — subscribes to SSE for live remap and playlist updates.",
  // 플레이어는 WebView에서 풀스크린으로 실행됨(MainActivity immersive 모드
  // 참조) — 사용자 줌을 막아 터치스크린 데모가 깨지지 않게 한다.
  viewport: {
    width: "device-width",
    initialScale: 1,
    maximumScale: 1,
    userScalable: false,
  },
};

interface PlayerRouteProps {
  params: { deviceId: string };
}

export default function PlayerPage({ params }: PlayerRouteProps) {
  const { deviceId } = params;
  return <PlayerClient deviceId={deviceId} />;
}
