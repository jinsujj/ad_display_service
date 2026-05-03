"use client";

/**
 * 어드민 셸(헤더 + main 컨테이너) — /player/ 라우트에서는 숨긴다.
 *
 * /player/{deviceId} 는 안드로이드 광고판 WebView 가 로드하는 페이지로,
 * 어드민 nav 가 보이면 광고 화면이 가려진다. usePathname 으로 클라이언트
 * 측에서 분기 — 광고판은 픽셀 한 줄도 어드민 UI 가 노출되지 않아야 한다.
 */

import type { ReactNode } from "react";
import { usePathname } from "next/navigation";
import { AuthHeader } from "./AuthHeader";

interface Props {
  children: ReactNode;
}

export function AppChrome({ children }: Props) {
  const pathname = usePathname() ?? "";
  const isPlayer = pathname.startsWith("/player");

  if (isPlayer) {
    // 광고판: chrome 없이 children 만 렌더 — PlayerClient 가 자체 풀스크린.
    return <>{children}</>;
  }

  return (
    <div className="app-shell">
      <header className="app-header">
        <a href="/" className="brand">
          AdSignage&nbsp;어드민
        </a>
        <nav className="primary-nav">
          <a href="/videos">영상</a>
          <a href="/ads">광고</a>
          <a href="/devices">디바이스</a>
        </nav>
        <AuthHeader />
      </header>
      <main className="app-main">{children}</main>
    </div>
  );
}

export default AppChrome;
