import Link from "next/link";
import { Suspense } from "react";

import { SignupForm } from "@/components/SignupForm";

export const dynamic = "force-dynamic";

export const metadata = {
  title: "회원가입 · AdSignage 어드민",
};

export default function SignupPage() {
  return (
    <section>
      <div className="page-header">
        <div>
          <h1>회원가입</h1>
          <div className="subtitle">
            이메일과 비밀번호로 광고주 계정을 만들면 가입 직후 자동 로그인됩니다.
          </div>
        </div>
      </div>

      <Suspense fallback={<div className="muted">불러오는 중…</div>}>
        <SignupForm />
      </Suspense>

      <p className="muted" style={{ marginTop: 24 }}>
        이미 가입하셨나요? <Link href="/login">로그인</Link>으로 이동하세요.
      </p>
    </section>
  );
}
