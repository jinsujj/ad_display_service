import type { Metadata } from "next";
import type { ReactNode } from "react";
import "./globals.css";

import { AppChrome } from "@/components/AppChrome";

const SITE_NAME = "AdSignage";
const SITE_TITLE = "AdSignage — 주류 냉장고 광고판 어드민";
const SITE_DESCRIPTION =
  "전국 음식점 주류 쇼케이스 냉장고 위 디지털 광고판. 광고 업로드, 시간/횟수 스케줄, 디바이스 모니터링까지 한 화면에서.";
const SITE_URL = "https://stream.owl-dev.me";

export const metadata: Metadata = {
  metadataBase: new URL(SITE_URL),
  title: {
    default: SITE_TITLE,
    template: "%s · " + SITE_NAME,
  },
  description: SITE_DESCRIPTION,
  applicationName: SITE_NAME,
  // Open Graph — 카카오톡 / 네이버 / 슬랙 / 페이스북 등 OG 파서가 사용.
  // og:image 는 같은 디렉토리의 opengraph-image.tsx 가 자동 생성하므로 여기 명시 불필요.
  openGraph: {
    type: "website",
    siteName: SITE_NAME,
    title: SITE_TITLE,
    description: SITE_DESCRIPTION,
    url: SITE_URL,
    locale: "ko_KR",
  },
  // 트위터/X — large_image 카드는 og:image 를 그대로 재활용.
  twitter: {
    card: "summary_large_image",
    title: SITE_TITLE,
    description: SITE_DESCRIPTION,
  },
  // 검색 봇이 인덱싱하도록 허용. 운영 데모 단계라면 이대로, 더 타이트하게
  // 막고 싶으면 robots: { index: false } 로.
  robots: {
    index: true,
    follow: true,
  },
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
        <AppChrome>{children}</AppChrome>
      </body>
    </html>
  );
}
