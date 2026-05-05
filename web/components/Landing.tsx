"use client";

/**
 * 미인증 사용자에게 보여주는 랜딩(마케팅) 페이지.
 *
 * 1. Hero — 한 줄 가치 제안 + 두 개 CTA(회원가입 / 로그인)
 * 2. Why  — 광고주 / 음식점 / 운영 세 관점
 * 3. How  — 1·2·3 step
 * 4. Final CTA
 *
 * 디자인은 어드민과 같은 토큰(앰버 + 다크). styled-jsx 에서 Tailwind 로 전환.
 */

import Link from "next/link";

import { Button } from "@/components/ui/button";

export function Landing() {
  return (
    <div className="flex flex-col gap-14 pb-6">
      {/* ========== Hero ========== */}
      <section className="grid items-center gap-10 pt-3 md:grid-cols-[minmax(0,1.15fr)_minmax(0,0.85fr)]">
        <div>
          <span className="mb-4 inline-block rounded-full border border-accent/35 bg-accent/15 px-2.5 py-1 text-xs font-semibold uppercase tracking-wider text-accent">
            디지털 사이니지 · B2B
          </span>
          <h1 className="mb-4 text-3xl font-extrabold leading-[1.18] tracking-tight md:text-4xl lg:text-[38px]">
            손님이{" "}
            <span className="text-accent">술 한 잔 따르는 그 자리</span>에서
            <br />
            광고가 송출됩니다
          </h1>
          <p className="mb-5 max-w-[60ch] text-[15.5px] leading-[1.7] text-muted-foreground">
            전국 음식점의 주류 쇼케이스 냉장고 위에 거치된 디지털 광고판.
            주류 메이커가 직접 영상을 올리고, 시간대·송출 횟수·캠페인 기간을
            정해 배포합니다. 음식점 사장님은 별도 작업 없이 광고 수익의
            일부를 분배받습니다.
          </p>
          <div className="flex flex-wrap items-center gap-2.5">
            <Button asChild>
              <Link href="/signup">광고주 회원가입</Link>
            </Button>
            <Button asChild variant="outline">
              <Link href="/login">이미 계정이 있으세요? 로그인</Link>
            </Button>
          </div>
          <ul className="mt-6 flex flex-col gap-2 text-[13.5px] text-muted-foreground">
            {[
              "주류 광고 매체 제한을 우회한 합법적 송출 채널",
              "실 소비 장소 도달 — 의사결정 직전 노출",
              "캠페인 기간 종료 시 자동 취소, 송출 증빙 로그 제공",
            ].map((line) => (
              <li
                key={line}
                className="relative pl-6 before:absolute before:left-0 before:top-0 before:font-bold before:text-accent before:content-['✓']"
              >
                {line}
              </li>
            ))}
          </ul>
        </div>

        {/* 광고판 모형 — 미세 펄스 인디케이터 */}
        <div
          className="flex flex-col items-center gap-3"
          aria-hidden="true"
        >
          <div className="relative flex aspect-[9/16] w-[min(280px,100%)] flex-col justify-center rounded-2xl border border-border bg-gradient-to-b from-[#14171c] to-[#0d1014] p-4 shadow-[0_30px_60px_-20px_rgba(0,0,0,0.6)] ring-1 ring-accent/10">
            <span
              aria-hidden="true"
              className="pointer-events-none absolute inset-2 rounded-xl bg-[radial-gradient(ellipse_at_50%_0%,rgba(245,176,66,0.08),transparent_60%)]"
            />
            <div className="absolute left-3.5 top-3.5 inline-flex items-center gap-1.5 rounded-full border border-rose-400/35 bg-rose-400/15 px-2 py-0.5 text-[10px] font-bold uppercase tracking-widest text-rose-400">
              <span className="size-1.5 animate-pill-pulse rounded-full bg-rose-400 shadow-[0_0_0_4px_rgba(255,107,107,0.18)]" />
              ON&nbsp;AIR
            </div>
            <div className="text-center">
              <div className="text-3xl font-extrabold tracking-tight text-accent [text-shadow:0_0_20px_rgba(245,176,66,0.35)]">
                진로하이트
              </div>
              <div className="mt-1.5 text-xs text-muted-foreground">
                5월 캠페인 · 11:00 ~ 23:00
              </div>
              <div className="mt-3.5 inline-block rounded-full border border-ad-border-soft bg-white/5 px-2.5 py-1 text-[11.5px] font-semibold">
                초기 30 / 일 송출
              </div>
            </div>
            <span
              aria-hidden="true"
              className="absolute -bottom-2.5 left-1/2 h-1.5 w-3/5 -translate-x-1/2 rounded-b-md border-t border-border bg-gradient-to-b from-[#1f242c] to-[#0b0d10]"
            />
          </div>
          <div className="text-xs text-muted-foreground">
            냉장고 상단 거치 · 10–15&quot; 세로 FHD
          </div>
        </div>
      </section>

      {/* ========== Why ========== */}
      <section>
        <h2 className="mb-4 text-lg font-bold tracking-tight">
          왜 이 자리인가
        </h2>
        <div className="grid gap-3.5 sm:grid-cols-2 lg:grid-cols-3">
          <WhyCard
            title="광고주에게"
            body="주류 광고가 허용되는 매체에 직접 배포. 시간대·요일·기간을 세팅하면 백엔드가 라운드 로빈으로 디바이스에 분배합니다. 송출 결과는 이벤트 단위로 집계되어 증빙 가능합니다."
          />
          <WhyCard
            title="음식점에게"
            body="디바이스 거치 외 추가 작업 없음. 광고 수익이 정산되어 들어오고, 매장 인테리어와 어울리는 세로형 디스플레이가 손님 동선에서 자연스럽게 보입니다."
          />
          <WhyCard
            title="운영에게"
            body="어드민에서 디바이스를 음식점에 매핑·재할당. 변경 즉시 SSE로 디바이스에 푸시되어 새 플레이리스트가 수 초 내 반영됩니다. 점포 이전·교체에도 대응이 자유롭습니다."
          />
        </div>
      </section>

      {/* ========== How ========== */}
      <section>
        <h2 className="mb-4 text-lg font-bold tracking-tight">
          어떻게 동작하나
        </h2>
        <ol className="flex flex-col gap-2.5">
          <Step
            no="1"
            title="광고주 회원가입 · MP4 업로드"
            sub="광고주는 어드민에서 영상을 올리고 시간대·일일 횟수·캠페인 기간으로 광고를 만듭니다."
          />
          <Step
            no="2"
            title="디바이스 ↔ 음식점 매핑"
            sub="운영자가 광고판 디바이스를 음식점에 매핑합니다. 매핑 변경은 SSE 푸시로 디바이스에 즉시 전달됩니다."
          />
          <Step
            no="3"
            title="송출 + 자동 취소"
            sub="광고판은 스케줄 시간대에만 라운드 로빈으로 송출하고, 캠페인 종료일을 지나면 자동으로 송출이 중단됩니다."
          />
        </ol>
      </section>

      {/* ========== Final CTA ========== */}
      <section className="flex flex-wrap items-center justify-between gap-5 rounded-xl border border-accent/25 bg-[linear-gradient(135deg,rgba(245,176,66,0.10),rgba(245,176,66,0))] bg-card p-6">
        <div>
          <div className="text-base font-bold">지금 시작해 보세요</div>
          <div className="mt-1 max-w-[56ch] text-[13.5px] text-muted-foreground">
            가입 후 영상 한 편을 올리면 5분 안에 첫 광고를 디바이스에 송출할
            수 있습니다.
          </div>
        </div>
        <div className="flex flex-wrap gap-2.5">
          <Button asChild>
            <Link href="/signup">광고주 회원가입</Link>
          </Button>
          <Button asChild variant="outline">
            <Link href="/login">로그인</Link>
          </Button>
        </div>
      </section>
    </div>
  );
}

function WhyCard({ title, body }: { title: string; body: string }) {
  return (
    <div className="rounded-lg border border-border bg-card p-5">
      <div className="mb-2.5 text-[11.5px] font-bold uppercase tracking-widest text-accent">
        {title}
      </div>
      <p className="m-0 text-[13.5px] leading-[1.7] text-muted-foreground">
        {body}
      </p>
    </div>
  );
}

function Step({
  no,
  title,
  sub,
}: {
  no: string;
  title: string;
  sub: string;
}) {
  return (
    <li className="flex items-start gap-4 rounded-lg border border-border bg-card p-4">
      <span className="inline-flex size-7 shrink-0 items-center justify-center rounded-full bg-accent/15 text-sm font-extrabold text-accent">
        {no}
      </span>
      <div>
        <div className="mb-1 font-semibold">{title}</div>
        <div className="text-[13.5px] leading-[1.6] text-muted-foreground">
          {sub}
        </div>
      </div>
    </li>
  );
}

export default Landing;
