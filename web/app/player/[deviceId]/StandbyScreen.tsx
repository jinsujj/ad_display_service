"use client";

/**
 * 디바이스가 송출할 광고가 없거나 시간대 밖일 때 띄우는 standby 화면.
 *
 * 디자인 원칙(매장 디지털 사이니지 컨텍스트 기준):
 *   1. *손님 화면이지 운영자 디버그 콘솔이 아니다* — "Waiting for SSE",
 *      "Connecting to AdSignage", deviceId 같은 기술 메시지는 절대 노출 X.
 *   2. 한국어 + 매장 분위기 — 시간대별 인사로 "살아있는 화면" 느낌.
 *   3. 거리(2-3m) + 환경광(매장 조명) 가정 → 큰 글씨, 강한 contrast.
 *   4. 정적 splash 한 장 → 미세한 그라디언트 모션으로 "TV 꺼진 줄" 인상 회피.
 *   5. 우하단 매장/시간 메타 — 아이덴티티는 살리되 카피는 작게.
 *
 * 시간대 인사:
 *   05~10  좋은 아침입니다  ☀️
 *   11~14  점심 맛있게 드세요  🍱
 *   15~17  잠시 쉬어가는 시간  ☕
 *   18~22  좋은 저녁입니다     🍻
 *   23~04  편안한 밤 되세요   🌙
 */

import { useEffect, useState } from "react";

interface Props {
  /** 매장 식별자 — backend playlist 응답의 restaurantId. 없으면 표시 생략. */
  restaurantId?: string | null;
  /** 표시할 보조 메시지. e.g. "다음 광고는 19:00 부터" */
  hint?: string;
}

const greetings: Array<{ from: number; to: number; emoji: string; text: string }> = [
  { from: 5, to: 10, emoji: "☀️", text: "좋은 아침입니다" },
  { from: 11, to: 14, emoji: "🍱", text: "점심 맛있게 드세요" },
  { from: 15, to: 17, emoji: "☕", text: "잠시 쉬어가는 시간" },
  { from: 18, to: 22, emoji: "🍻", text: "좋은 저녁입니다" },
  // 23~04 는 wrap-around — pickGreeting 에서 처리
];

function pickGreeting(hour: number): { emoji: string; text: string } {
  for (const g of greetings) {
    if (hour >= g.from && hour <= g.to) return { emoji: g.emoji, text: g.text };
  }
  // 23~04 wrap
  return { emoji: "🌙", text: "편안한 밤 되세요" };
}

function pad2(n: number): string {
  return n < 10 ? "0" + n : String(n);
}

export function StandbyScreen({ restaurantId, hint }: Props) {
  // 시계 / 인사 갱신을 위해 매 30초마다 리렌더. setInterval 한 번.
  const [now, setNow] = useState<Date>(() => new Date());
  useEffect(() => {
    const id = setInterval(() => setNow(new Date()), 30_000);
    return () => clearInterval(id);
  }, []);

  const greeting = pickGreeting(now.getHours());
  const clock = `${pad2(now.getHours())}:${pad2(now.getMinutes())}`;

  return (
    <div className="standby" role="status" aria-live="polite">
      {/* 모션 그라디언트 — 살아있다는 느낌만 주는 미세한 펄스 */}
      <div className="standby__bg" aria-hidden="true" />
      {/* 노이즈 텍스처 (subtle) — 평탄한 그라디언트의 banding 방지 */}
      <div className="standby__noise" aria-hidden="true" />

      <div className="standby__center">
        <div className="standby__emoji" aria-hidden="true">
          {greeting.emoji}
        </div>
        <h1 className="standby__title">{greeting.text}</h1>
        <p className="standby__sub">
          {hint ?? "잠시 후 광고가 다시 송출됩니다"}
        </p>
      </div>

      {/* 우하단 메타 워터마크 */}
      <div className="standby__meta" aria-hidden="true">
        <div className="standby__brand">AdSignage</div>
        {restaurantId && (
          <div className="standby__loc">📍 {restaurantId.slice(0, 8)}</div>
        )}
        <div className="standby__clock">{clock}</div>
      </div>

      <style jsx>{`
        .standby {
          position: absolute;
          inset: 0;
          background: #0b0d10;
          color: #f6f7f8;
          overflow: hidden;
          font-family: "Pretendard", system-ui, -apple-system, sans-serif;
        }

        .standby__bg {
          position: absolute;
          inset: -10%;
          background: radial-gradient(
              ellipse at 30% 20%,
              rgba(245, 176, 66, 0.18) 0%,
              rgba(245, 176, 66, 0) 45%
            ),
            radial-gradient(
              ellipse at 75% 80%,
              rgba(120, 80, 200, 0.15) 0%,
              rgba(120, 80, 200, 0) 50%
            ),
            linear-gradient(135deg, #0b0d10 0%, #14171c 60%, #1a1f27 100%);
          animation: drift 22s ease-in-out infinite alternate;
          will-change: transform, opacity;
        }

        .standby__noise {
          position: absolute;
          inset: 0;
          background-image: radial-gradient(
            rgba(255, 255, 255, 0.018) 1px,
            transparent 1px
          );
          background-size: 3px 3px;
          opacity: 0.6;
          mix-blend-mode: overlay;
          pointer-events: none;
        }

        @keyframes drift {
          0% {
            transform: scale(1) translate(0, 0);
            opacity: 0.95;
          }
          100% {
            transform: scale(1.06) translate(-2%, 1%);
            opacity: 1;
          }
        }

        .standby__center {
          position: absolute;
          inset: 0;
          display: flex;
          flex-direction: column;
          align-items: center;
          justify-content: center;
          text-align: center;
          padding: 6vw;
          gap: 1.2vw;
          z-index: 1;
        }

        .standby__emoji {
          font-size: clamp(72px, 12vw, 180px);
          line-height: 1;
          margin-bottom: 1vw;
          animation: bob 4s ease-in-out infinite;
        }

        @keyframes bob {
          0%, 100% {
            transform: translateY(0);
          }
          50% {
            transform: translateY(-6px);
          }
        }

        .standby__title {
          font-size: clamp(40px, 6vw, 96px);
          font-weight: 700;
          letter-spacing: -0.02em;
          margin: 0;
          /* 한국어 줄바꿈 자연스럽게 */
          word-break: keep-all;
          line-height: 1.15;
          /* 강한 contrast — 매장 환경광에서도 읽힘 */
          text-shadow: 0 4px 24px rgba(0, 0, 0, 0.6);
        }

        .standby__sub {
          font-size: clamp(20px, 2.4vw, 36px);
          font-weight: 400;
          color: rgba(246, 247, 248, 0.7);
          margin: 0;
          word-break: keep-all;
        }

        /* 우하단 메타 — 작게 + 워터마크 톤 */
        .standby__meta {
          position: absolute;
          right: 3vw;
          bottom: 3vw;
          display: flex;
          flex-direction: column;
          align-items: flex-end;
          gap: 2px;
          color: rgba(246, 247, 248, 0.45);
          font-size: clamp(11px, 1vw, 16px);
          letter-spacing: 0.04em;
          z-index: 1;
        }
        .standby__brand {
          font-weight: 700;
          letter-spacing: 0.12em;
          color: rgba(245, 176, 66, 0.75);
          font-size: clamp(11px, 1vw, 16px);
        }
        .standby__clock {
          font-variant-numeric: tabular-nums;
          font-feature-settings: "tnum";
          color: rgba(246, 247, 248, 0.65);
          font-size: clamp(20px, 2.2vw, 36px);
          font-weight: 600;
          margin-top: 4px;
        }

        /* 좌상단 LIVE 인디케이터는 의도적으로 *없음* —
           운영자 디버그 메시지는 어드민에서 보고, 손님 화면은 콘텐츠에만 집중. */
      `}</style>
    </div>
  );
}

export default StandbyScreen;
