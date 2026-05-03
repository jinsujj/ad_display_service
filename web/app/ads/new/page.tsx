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
      <section>
        <div className="page-header">
          <div>
            <h1>새 광고 만들기</h1>
            <div className="subtitle">
              업로드된 영상에 제목과 일일 송출 스케줄을 묶어 광고로 등록합니다.
            </div>
          </div>
          <Link href="/ads" className="btn">← 광고 목록</Link>
        </div>

        <Suspense fallback={<div className="muted">불러오는 중…</div>}>
          <CreateAdForm />
        </Suspense>

        <p className="muted" style={{ marginTop: 24 }}>
          영상 파일명을 모르겠다면 <Link href="/videos">영상 페이지</Link>에서
          업로드된 영상의 "광고로 만들기" 버튼을 누르면 자동으로 채워집니다.
        </p>
      </section>
    </AuthGuard>
  );
}
