import Link from "next/link";
import { Suspense } from "react";

import { LoginForm } from "@/components/LoginForm";

export const dynamic = "force-dynamic";

export const metadata = {
  title: "로그인 · AdSignage 어드민",
};

export default function LoginPage() {
  return (
    <section className="mx-auto w-full max-w-narrow">
      <header className="mb-6">
        <h1 className="text-2xl font-semibold tracking-tight">로그인</h1>
        <p className="mt-1 text-sm text-muted-foreground">
          광고주 계정으로 로그인하면 영상 업로드와 광고 스케줄 편집이 가능합니다.
        </p>
      </header>

      <Suspense
        fallback={
          <div className="text-sm text-muted-foreground">불러오는 중…</div>
        }
      >
        <LoginForm />
      </Suspense>

      <p className="mt-6 text-sm text-muted-foreground">
        아직 계정이 없으신가요?{" "}
        <Link
          href="/signup"
          className="text-accent underline-offset-4 hover:underline"
        >
          회원가입
        </Link>
        으로 이동하세요.
      </p>
    </section>
  );
}
