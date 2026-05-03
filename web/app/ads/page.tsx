/**
 * 광고 인덱스 페이지 (`/ads`).
 *
 * - 새 광고 만들기 진입점 (/ads/new)
 * - 내 광고 목록 (GET /api/ads via MyAdsList — 클라이언트 컴포넌트)
 * - 기존 ID 직접 입력 폼 (스케줄 페이지로 이동)
 */

import Link from "next/link";

import { AdLookupForm } from "@/components/AdLookupForm";
import { AuthGuard } from "@/components/AuthGuard";
import { MyAdsList } from "@/components/MyAdsList";

export const dynamic = "force-dynamic";

export const metadata = {
  title: "광고 · AdSignage 어드민",
};

export default function AdsIndexPage() {
  return (
    <AuthGuard>
      <section>
        <div className="page-header">
          <div>
            <h1>광고</h1>
            <div className="subtitle">
              영상으로 광고를 만들고 일일 송출 스케줄을 관리합니다.
            </div>
          </div>
          <Link href="/ads/new" className="btn">+ 새 광고 만들기</Link>
        </div>

        <h2 className="section-heading">내 광고</h2>
        <MyAdsList />

        <h2 className="section-heading" style={{ marginTop: 32 }}>
          광고 ID로 직접 열기
        </h2>
        <p className="muted" style={{ marginTop: 0 }}>
          이미 알고 있는 광고 UUID가 있으면 아래에 붙여 넣어 스케줄 편집
          페이지로 바로 이동할 수 있습니다.
        </p>
        <AdLookupForm />
      </section>
    </AuthGuard>
  );
}
