import Link from "next/link";
import { Suspense } from "react";

import { AuthGuard } from "@/components/AuthGuard";
import { CreateAdForm } from "@/components/CreateAdForm";

export const dynamic = "force-dynamic";

export const metadata = {
  title: "새 광고 만들기 · AdSignage 어드민",
};

export default function NewAdPage() {
  return (
    <AuthGuard>
      <section className="mx-auto w-full max-w-narrow space-y-6">
        <header className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
          <div>
            <h1 className="text-2xl font-semibold tracking-tight">
              새 광고 만들기
            </h1>
            <p className="mt-1 text-sm text-muted-foreground">
              업로드된 영상에 제목과 일일 송출 스케줄을 묶어 광고로
              등록합니다.
            </p>
          </div>
          <Link
            href="/ads"
            className="inline-flex h-11 items-center justify-center rounded-md border border-input bg-background px-4 text-sm font-medium transition-colors hover:bg-accent/15 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2"
          >
            ← 광고 목록
          </Link>
        </header>

        <Suspense
          fallback={
            <div className="text-sm text-muted-foreground">불러오는 중…</div>
          }
        >
          <CreateAdForm />
        </Suspense>

        <p className="text-sm text-muted-foreground">
          영상 파일명을 모르겠다면{" "}
          <Link
            href="/videos"
            className="text-accent underline-offset-4 hover:underline"
          >
            영상 페이지
          </Link>
          에서 업로드된 영상의 &quot;광고로 만들기&quot; 버튼을 누르면 자동으로
          채워집니다.
        </p>
      </section>
    </AuthGuard>
  );
}
