/**
 * 동적 OG 이미지 생성 — Next.js 14 의 `app/opengraph-image.tsx` 컨벤션.
 *
 * 빌드 타임/요청 시점에 [ImageResponse] 가 1200x630 PNG 를 만들어 `og:image`
 * 메타태그로 자동 노출된다. 카카오톡/네이버 메신저/슬랙/페이스북 등이
 * 링크 미리보기로 이 이미지를 가져간다.
 *
 * 디자인 요지:
 *   - 다크 배경 + 앰버 액센트 (`--accent #f5b042`) — 어드민 UI 와 동일 톤
 *   - 큰 한국어 헤드라인 + 영문 사이트명 + 핵심 가치 한 줄
 *   - "주류 냉장고 광고판" 이라는 도메인을 한눈에 전달
 *
 * 외부 폰트 fetch 없이 system 폰트만 사용 — Pretendard 까지 끌어오면
 * 빌드/엣지 함수에서 fetch 가 무거워지고 실패 가능성도 커진다. system
 * 폰트의 한국어 글리프 fallback (보통 Apple SD Gothic / Malgun Gothic) 으로
 * 충분히 읽힘.
 */

import { ImageResponse } from "next/og";

// 빌드 시점에 한 번만 생성해 정적 PNG 로 서빙. 매 요청마다 satori 가
// 렌더 비용을 부담하지 않고 nginx/CDN 캐시도 활용 — 카카오·페이스북 스크래퍼
// 같은 짧은 timeout 클라이언트도 안정적으로 가져간다.
export const dynamic = "force-static";

// OG 표준 권장 사이즈. 페이스북/카카오/슬랙 모두 이 비율을 잘 처리한다.
export const size = {
  width: 1200,
  height: 630,
};

export const contentType = "image/png";

export const alt =
  "AdSignage — 주류 냉장고 광고판 어드민";

export default function OpengraphImage() {
  return new ImageResponse(
    (
      <div
        style={{
          width: "100%",
          height: "100%",
          display: "flex",
          flexDirection: "column",
          justifyContent: "space-between",
          padding: "72px 80px",
          background:
            "linear-gradient(135deg, #0b0d10 0%, #14171c 60%, #1a1f27 100%)",
          color: "#f6f7f8",
          fontFamily: "system-ui, -apple-system, sans-serif",
        }}
      >
        {/* 상단: 사이트 식별 */}
        <div
          style={{
            display: "flex",
            alignItems: "center",
            gap: 16,
            fontSize: 28,
            color: "#9aa1a9",
            letterSpacing: 2,
          }}
        >
          <span
            style={{
              // satori 는 display: "inline-block" 미지원 — flex/block/none 만 허용.
              display: "flex",
              width: 14,
              height: 14,
              borderRadius: "50%",
              background: "#f5b042",
              boxShadow: "0 0 16px rgba(245,176,66,0.6)",
            }}
          />
          <span style={{ textTransform: "uppercase" }}>AdSignage</span>
          <span style={{ opacity: 0.4 }}>·</span>
          <span>주류 냉장고 디지털 광고판</span>
        </div>

        {/* 중앙: 헤드라인 */}
        <div style={{ display: "flex", flexDirection: "column", gap: 18 }}>
          <div
            style={{
              fontSize: 78,
              fontWeight: 700,
              lineHeight: 1.15,
              letterSpacing: "-1px",
              display: "flex",
              flexDirection: "column",
              gap: 4,
            }}
          >
            <span>손님이 술 한 잔 따르는</span>
            <span>
              그 자리에서{" "}
              <span style={{ color: "#f5b042" }}>광고가 송출</span>됩니다
            </span>
          </div>
          <div
            style={{
              fontSize: 30,
              color: "#9aa1a9",
              lineHeight: 1.4,
              maxWidth: 920,
            }}
          >
            전국 음식점의 주류 쇼케이스 냉장고 위 디지털 광고판. 광고 업로드,
            시간·횟수 스케줄, 디바이스 모니터링까지 한 화면에서.
          </div>
        </div>

        {/* 하단: URL + bullet */}
        <div
          style={{
            display: "flex",
            alignItems: "center",
            justifyContent: "space-between",
            fontSize: 22,
          }}
        >
          <div
            style={{
              display: "flex",
              gap: 24,
              color: "#c9ced5",
              fontWeight: 500,
            }}
          >
            <span>· 광고주 어드민</span>
            <span>· 디바이스 모니터링</span>
            <span>· 캠페인 스케줄</span>
          </div>
          <div
            style={{
              color: "#f5b042",
              fontWeight: 600,
              letterSpacing: 1,
            }}
          >
            stream.owl-dev.me
          </div>
        </div>
      </div>
    ),
    {
      ...size,
    },
  );
}
