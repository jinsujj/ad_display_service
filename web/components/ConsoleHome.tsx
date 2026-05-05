"use client";

/**
 * 인증된 사용자에게 보여주는 홈 — 어드민 콘솔의 진입 카드.
 *
 * 헤더에 이미 영상/광고/디바이스 nav 가 있지만, 큰 화면에서 카드형으로
 * 하나 더 보여주면 신규 가입자가 첫 흐름(영상 업로드 → 광고 만들기 →
 * 디바이스 매핑)을 직관적으로 따라간다.
 */

import Link from "next/link";
import { useEffect, useState } from "react";

import { readStoredAuthUser } from "@/lib/auth";

export function ConsoleHome() {
  const [email, setEmail] = useState<string | null>(null);
  useEffect(() => {
    setEmail(readStoredAuthUser()?.email ?? null);
  }, []);

  return (
    <section className="space-y-6 pb-3">
      <header>
        <h1 className="text-2xl font-semibold tracking-tight">
          {email
            ? `${email.split("@")[0]} 님, 환영합니다`
            : "AdSignage 어드민"}
        </h1>
        <p className="mt-1 text-sm text-muted-foreground">
          영상 업로드 → 광고 만들기 → 디바이스 매핑 순으로 진행하면 첫
          송출까지 5분이면 충분합니다.
        </p>
      </header>

      <div className="grid grid-cols-[repeat(auto-fit,minmax(220px,1fr))] gap-3.5">
        <ConsoleCard
          href="/videos"
          step="01"
          title="영상"
          desc="업로드된 MP4 광고 영상을 확인하고 새 영상을 올립니다."
        />
        <ConsoleCard
          href="/ads"
          step="02"
          title="광고"
          desc="영상에 일일 시간 윈도우와 캠페인 기간을 묶어 광고를 만들고 스케줄을 편집합니다."
        />
        <ConsoleCard
          href="/devices"
          step="03"
          title="디바이스"
          desc="등록된 광고판을 음식점에 매핑·재할당합니다. 변경은 SSE로 즉시 푸시됩니다."
        />
        <ConsoleCard
          href="/profile"
          step="04"
          title="프로필"
          desc="광고주 계정 정보와 비밀번호를 관리합니다."
        />
      </div>
    </section>
  );
}

interface ConsoleCardProps {
  href: string;
  step: string;
  title: string;
  desc: string;
  external?: boolean;
  muted?: boolean;
}

function ConsoleCard({
  href,
  step,
  title,
  desc,
  external,
  muted,
}: ConsoleCardProps) {
  const className =
    "group relative flex min-h-[156px] flex-col overflow-hidden rounded-lg border border-border bg-card p-5 text-card-foreground no-underline transition-colors hover:border-accent/40 hover:bg-secondary/40";
  const stepClass = muted
    ? "mb-2 text-[11px] font-bold uppercase tracking-widest text-muted-foreground"
    : "mb-2 text-[11px] font-bold uppercase tracking-widest text-accent";

  const content = (
    <>
      <div className={stepClass}>{step}</div>
      <div className="mb-1.5 text-lg font-bold">{title}</div>
      <div className="text-[13px] leading-relaxed text-muted-foreground">
        {desc}
      </div>
      <span
        aria-hidden="true"
        className="absolute bottom-3 right-4 text-lg text-muted-foreground transition-[color,transform] group-hover:translate-x-1 group-hover:text-accent"
      >
        →
      </span>
    </>
  );

  if (external) {
    return (
      <a
        href={href}
        target="_blank"
        rel="noopener noreferrer"
        className={className}
      >
        {content}
      </a>
    );
  }
  return (
    <Link href={href} className={className}>
      {content}
    </Link>
  );
}

export default ConsoleHome;
