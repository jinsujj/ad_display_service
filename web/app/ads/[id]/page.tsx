/**
 * 광고 상세/스케줄 편집 페이지 (`/ads/[id]`).
 *
 * Server Component 셸 — 인증과 라우팅만 담당하고 실제 광고 데이터 fetch
 * 와 폼 prefill 은 [AdEditClient] (클라이언트 컴포넌트) 가 처리한다.
 * 토큰이 localStorage 에 있어 SSR fetch 가 401 이 되는 문제를 회피.
 */

import Link from "next/link";

import { AuthGuard } from "@/components/AuthGuard";
import { AdEditClient } from "@/components/AdEditClient";

export const dynamic = "force-dynamic";

export const metadata = {
  title: "광고 스케줄 편집 · AdSignage 어드민",
};

interface AdEditPageProps {
  params: { id: string };
}

export default function AdEditPage({ params }: AdEditPageProps) {
  const adId = params.id;

  return (
    <AuthGuard>
      <section className="space-y-6">
        <header className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
          <div>
            <h1 className="text-2xl font-semibold tracking-tight">
              광고 스케줄 편집
            </h1>
            <p className="mt-1 text-sm text-muted-foreground">
              이 광고의 일일 송출 윈도우(시작/종료 시간)와 목표 송출 횟수를
              수정합니다. 저장 즉시 적용되며 다음 플레이리스트 새로고침에
              반영됩니다.
            </p>
          </div>
          <Link
            href="/ads"
            className="inline-flex h-11 items-center justify-center rounded-md border border-input bg-background px-4 text-sm font-medium transition-colors hover:bg-accent/15 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2"
          >
            ← 광고 목록으로
          </Link>
        </header>

        <AdEditClient adId={adId} />
      </section>
    </AuthGuard>
  );
}
