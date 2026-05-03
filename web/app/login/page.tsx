import Link from "next/link";
import { Suspense } from "react";

import { LoginForm } from "@/components/LoginForm";

export const dynamic = "force-dynamic";

export const metadata = {
  title: "로그인 · AdSignage 어드민",
};

export default function LoginPage() {
  return (
    <section>
      <div className="page-header">
        <div>
          <h1>로그인</h1>
          <div className="subtitle">
            광고주 계정으로 로그인하면 영상 업로드와 광고 스케줄 편집이 가능합니다.
          </div>
        </div>
      </div>

      <Suspense fallback={<div className="muted">불러오는 중…</div>}>
        <LoginForm />
      </Suspense>

      <p className="muted" style={{ marginTop: 24 }}>
        아직 계정이 없으신가요? <Link href="/signup">회원가입</Link>으로 이동하세요.
      </p>
    </section>
  );
}
