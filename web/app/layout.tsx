import type { Metadata } from "next";
import type { ReactNode } from "react";
import "./globals.css";

import { AuthHeader } from "@/components/AuthHeader";

export const metadata: Metadata = {
  title: "AdSignage 어드민",
  description:
    "음식점 주류 냉장고 디지털 광고판 어드민 — 광고주, 광고/스케줄, 디바이스 매핑을 관리합니다.",
};

export default function RootLayout({ children }: { children: ReactNode }) {
  return (
    <html lang="ko">
      <head>
        {/* Pretendard 가변 폰트 — 한국어 친화 시스템 폰트가 없는 환경에 폴백 */}
        <link
          rel="stylesheet"
          href="https://cdn.jsdelivr.net/gh/orioncactus/pretendard@v1.3.9/dist/web/variable/pretendardvariable.css"
        />
      </head>
      <body>
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
      </body>
    </html>
  );
}
