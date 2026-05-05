"use client";

/**
 * 미인증 사용자에게 보여주는 랜딩(마케팅) 페이지.
 *
 * 1. Hero — 한 줄 가치 제안 + 두 개 CTA(회원가입 / 로그인)
 * 2. Why  — 광고주 / 음식점 / 운영 세 관점
 * 3. How  — 1·2·3 step
 * 4. Final CTA
 *
 * Mock device: 쇼케이스 냉장고 위에 광고 패널이 거치된 모습.
 * - 상단 광고 패널: 16:9 가로 디스플레이에 진로 광고(YouTube 임베드) 자동
 *   재생. mute=1 로 모바일 자동재생 정책 준수. loop+playlist 트릭으로 무한
 *   반복.
 * - 하단 쇼케이스 냉장고: 글라스 도어 안 3 단 선반에 소주(녹색)·맥주(갈색)
 *   병이 진열된 비주얼.
 * - 영상 교체: NEXT_PUBLIC_LANDING_VIDEO_ID 에 YouTube ID.
 */

import Link from "next/link";

import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";

// 하이트진로 공식 채널 "진로 CF : 꺼비월드 편(15초)" 기본값.
const LANDING_VIDEO_ID =
  process.env.NEXT_PUBLIC_LANDING_VIDEO_ID || "aWBIufbD6aE";

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

        {/* 쇼케이스 냉장고 + 상단 광고 패널 모형 */}
        <FridgeWithAdPanel />
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

/* ========== 쇼케이스 냉장고 + 상단 광고 패널 ========================== */

function FridgeWithAdPanel() {
  return (
    <div
      className="mx-auto flex w-[min(300px,100%)] flex-col items-stretch"
      aria-hidden="true"
    >
      {/* 광고 패널 — 16:9 디지털 사이니지, 베젤·플라스틱 케이스 인상 */}
      <div className="relative aspect-video w-full overflow-hidden rounded-md border border-black/40 bg-black p-[3px] shadow-[0_18px_30px_-18px_rgba(0,0,0,0.7)] ring-1 ring-accent/15">
        {/* 패널 화면 */}
        <div className="relative h-full w-full overflow-hidden rounded-sm bg-black">
          <iframe
            title="진로 소주 광고 — 하이트진로 공식 채널"
            src={`https://www.youtube-nocookie.com/embed/${LANDING_VIDEO_ID}?autoplay=1&mute=1&loop=1&playlist=${LANDING_VIDEO_ID}&controls=0&modestbranding=1&rel=0&playsinline=1&iv_load_policy=3&disablekb=1`}
            allow="autoplay; encrypted-media; picture-in-picture"
            allowFullScreen={false}
            className="absolute inset-0 h-full w-full border-0"
          />
          {/* ON AIR 배지 */}
          <div className="absolute left-2 top-2 z-10 inline-flex items-center gap-1.5 rounded-full border border-rose-400/40 bg-rose-500/25 px-2 py-0.5 text-[9px] font-bold uppercase tracking-widest text-rose-100 backdrop-blur-sm">
            <span className="size-1.5 animate-pill-pulse rounded-full bg-rose-400 shadow-[0_0_0_3px_rgba(255,107,107,0.2)]" />
            ON&nbsp;AIR
          </div>
          {/* 광고판 빛 글로우 */}
          <span className="pointer-events-none absolute inset-0 bg-[radial-gradient(ellipse_at_50%_-20%,rgba(245,176,66,0.16),transparent_60%)]" />
        </div>
      </div>

      {/* 마운팅 브래킷 — 광고 패널이 냉장고 위에 거치된 인상 */}
      <div className="mx-auto -mt-px flex w-[28%] justify-center">
        <span className="block h-2 w-full rounded-b-sm border-x border-b border-black/50 bg-gradient-to-b from-[#2a2e36] to-[#13161a] shadow-[inset_0_-1px_0_rgba(255,255,255,0.04)]" />
      </div>

      {/* 쇼케이스 냉장고 — 글라스 도어 + 3단 선반 + 술병들 + 브랜드 타이틀 */}
      <div className="relative mt-2 aspect-[10/13] w-full overflow-hidden rounded-md border border-[#1a1d22] bg-gradient-to-b from-[#0d1218] via-[#101820] to-[#080c11] shadow-[0_24px_40px_-24px_rgba(0,0,0,0.8)]">
        {/* 상단 헤더 — 냉장고 타이틀(브랜드 라이트박스 느낌) */}
        <div className="absolute inset-x-0 top-0 flex items-center justify-center border-b border-black/60 bg-gradient-to-b from-[#1a1f26] to-[#0e1217] py-1.5 text-[10px] font-bold tracking-[0.25em] text-amber-300/80 [text-shadow:0_0_8px_rgba(245,176,66,0.4)]">
          쇼케이스 냉장고
        </div>

        {/* 측면 크롬 트림 */}
        <span className="absolute inset-y-0 left-0 w-1.5 bg-gradient-to-r from-[#2a2e36] via-[#1a1d22] to-transparent" />
        <span className="absolute inset-y-0 right-0 w-1.5 bg-gradient-to-l from-[#2a2e36] via-[#1a1d22] to-transparent" />

        {/* 글라스 도어 내부 — 차가운 푸른빛 + 미세 반사 */}
        <div className="absolute inset-x-1.5 inset-y-[26px] overflow-hidden rounded-sm bg-[linear-gradient(180deg,rgba(96,165,250,0.10)_0%,rgba(56,89,140,0.18)_45%,rgba(15,30,50,0.35)_100%)]">
          {/* 글라스 반사 — 좌상단 사선 하이라이트 */}
          <span className="pointer-events-none absolute -left-2 -top-2 h-[60%] w-[55%] rotate-[-18deg] bg-[linear-gradient(110deg,rgba(255,255,255,0.10)_0%,rgba(255,255,255,0.04)_30%,transparent_60%)]" />
          {/* 안쪽 LED 백라이트 글로우 */}
          <span className="pointer-events-none absolute inset-0 bg-[radial-gradient(ellipse_at_50%_15%,rgba(173,216,255,0.10),transparent_55%)]" />

          {/* 3 단 선반 + 술병 */}
          <Shelf top="14%" bottles={["green", "green", "green", "green", "green", "green"]} />
          <Shelf top="44%" bottles={["green", "brown", "green", "brown", "green", "brown"]} />
          <Shelf top="74%" bottles={["clear", "clear", "green", "green", "brown", "brown"]} />
        </div>

        {/* 도어 손잡이 (오른쪽 세로 바) */}
        <span className="absolute right-2 top-1/2 h-[55%] w-1 -translate-y-1/2 rounded-full bg-gradient-to-b from-[#3a3f48] via-[#2a2e36] to-[#15181d] shadow-[inset_0_1px_0_rgba(255,255,255,0.08)]" />

        {/* 하단 베이스 / 다리 */}
        <span className="absolute inset-x-0 bottom-0 h-2.5 bg-gradient-to-t from-black to-[#0d1218]" />
      </div>

      <div className="mt-3 text-center text-xs text-muted-foreground">
        쇼케이스 냉장고 상단 광고 패널 · 10–15&quot; 디지털 사이니지
      </div>
    </div>
  );
}

/* ----- 선반 한 줄 + 그 위 술병들 ------------------------------------- */

type BottleColor = "green" | "brown" | "clear";

function Shelf({
  top,
  bottles,
}: {
  top: string;
  bottles: BottleColor[];
}) {
  return (
    <>
      {/* 술병들 — 선반 윗면에 늘어선 모습 */}
      <div
        className="absolute inset-x-2 flex items-end justify-around"
        style={{ top: `calc(${top} - 22px)`, height: 22 }}
      >
        {bottles.map((c, i) => (
          <Bottle key={i} color={c} />
        ))}
      </div>
      {/* 금속 선반 라인 */}
      <span
        className="absolute inset-x-1 h-[2px] rounded-sm bg-gradient-to-b from-[#3a4250] to-[#1d2230] shadow-[0_1px_0_rgba(0,0,0,0.6)]"
        style={{ top }}
      />
    </>
  );
}

function Bottle({ color }: { color: BottleColor }) {
  const bodyTint = cn(
    color === "green" &&
      "bg-[linear-gradient(180deg,#1d4d36_0%,#2a7a4f_35%,#1f5c3a_70%,#0e2a1c_100%)]",
    color === "brown" &&
      "bg-[linear-gradient(180deg,#3d220e_0%,#7a4922_35%,#5c3617_70%,#1f1108_100%)]",
    color === "clear" &&
      "bg-[linear-gradient(180deg,rgba(220,235,240,0.5)_0%,rgba(160,190,210,0.55)_35%,rgba(110,140,160,0.45)_70%,rgba(40,60,80,0.5)_100%)]",
  );
  // 미세 좌측 하이라이트로 유리 질감
  const highlight =
    "before:content-[''] before:absolute before:inset-y-1 before:left-[1.5px] before:w-[1.5px] before:rounded-full before:bg-white/15";
  return (
    <span className="relative flex flex-col items-center">
      {/* 마개 / 캡 */}
      <span
        className={cn(
          "block h-1 w-[5px] rounded-t-[1px]",
          color === "green" && "bg-rose-700/80",
          color === "brown" && "bg-yellow-200/70",
          color === "clear" && "bg-amber-300/70",
        )}
      />
      {/* 목 */}
      <span
        className={cn(
          "block h-[3px] w-[3px]",
          color === "green" && "bg-emerald-900",
          color === "brown" && "bg-amber-950",
          color === "clear" && "bg-slate-500/60",
        )}
      />
      {/* 어깨 + 몸통 */}
      <span
        className={cn(
          "relative block h-[14px] w-[8px] rounded-t-[3px] rounded-b-[1px]",
          bodyTint,
          highlight,
        )}
      />
    </span>
  );
}

/* ========== 마케팅 카드 / 단계 ============================ */

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
