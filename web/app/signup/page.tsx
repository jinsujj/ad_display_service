import Link from "next/link";
import { Suspense } from "react";

import { SignupForm } from "@/components/SignupForm";

export const dynamic = "force-dynamic";

export const metadata = {
  title: "회원가입 · AdSignage 어드민",
};

export default function SignupPage() {
  return (
    <section className="mx-auto w-full max-w-narrow">
      <header className="mb-6">
        <h1 className="text-2xl font-semibold tracking-tight">회원가입</h1>
        <p className="mt-1 text-sm text-muted-foreground">
          이메일과 비밀번호로 광고주 계정을 만들면 가입 직후 자동 로그인됩니다.
        </p>
      </header>

      <Suspense
        fallback={
          <div className="text-sm text-muted-foreground">불러오는 중…</div>
        }
      >
        <SignupForm />
      </Suspense>

      <p className="mt-6 text-sm text-muted-foreground">
        이미 가입하셨나요?{" "}
        <Link
          href="/login"
          className="text-accent underline-offset-4 hover:underline"
        >
          로그인
        </Link>
        으로 이동하세요.
      </p>
    </section>
  );
}
