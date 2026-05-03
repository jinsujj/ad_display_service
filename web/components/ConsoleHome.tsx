"use client";

/**
 * 인증된 사용자에게 보여주는 홈 — 어드민 콘솔의 진입 카드.
 *
 * 헤더에 이미 영상/광고/디바이스 nav 가 있지만, 큰 화면에서 카드형으로
 * 하나 더 보여주면 신규 가입자가 첫 흐름(영상 업로드 → 광고 만들기 → 디바이스
 * 매핑)을 직관적으로 따라간다.
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
    <section className="console-home">
      <div className="page-header">
        <div>
          <h1>{email ? `${email.split("@")[0]} 님, 환영합니다` : "AdSignage 어드민"}</h1>
          <div className="subtitle">
            영상 업로드 → 광고 만들기 → 디바이스 매핑 순으로 진행하면 첫 송출까지 5분이면 충분합니다.
          </div>
        </div>
      </div>

      <div className="console-grid">
        <Link href="/videos" className="console-card">
          <div className="console-card__no">01</div>
          <div className="console-card__title">영상</div>
          <div className="console-card__desc">
            업로드된 MP4 광고 영상을 확인하고 새 영상을 올립니다.
          </div>
        </Link>

        <Link href="/ads" className="console-card">
          <div className="console-card__no">02</div>
          <div className="console-card__title">광고</div>
          <div className="console-card__desc">
            영상에 일일 시간 윈도우와 캠페인 기간을 묶어 광고를 만들고 스케줄을 편집합니다.
          </div>
        </Link>

        <Link href="/devices" className="console-card">
          <div className="console-card__no">03</div>
          <div className="console-card__title">디바이스</div>
          <div className="console-card__desc">
            등록된 광고판을 음식점에 매핑·재할당합니다. 변경은 SSE로 즉시 푸시됩니다.
          </div>
        </Link>

        <a
          href="https://stream-backend.owl-dev.me/swagger-ui/index.html"
          target="_blank"
          rel="noopener noreferrer"
          className="console-card console-card--muted"
        >
          <div className="console-card__no">API</div>
          <div className="console-card__title">Swagger UI</div>
          <div className="console-card__desc">
            REST API 명세를 대화형으로 호출해 봅니다. 우상단 Authorize에 JWT를 입력하세요.
          </div>
        </a>
      </div>

      <style jsx>{`
        .console-home { padding-bottom: 12px; }
        .console-grid {
          display: grid;
          grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
          gap: 14px;
          margin-top: 16px;
        }
        .console-card {
          display: flex; flex-direction: column;
          padding: 22px; min-height: 156px;
          background: var(--bg-elev);
          border: 1px solid var(--border);
          border-radius: var(--radius);
          color: var(--fg);
          text-decoration: none;
          border-bottom: 1px solid var(--border);
          transition: border-color .15s, transform .08s, background .15s;
          position: relative; overflow: hidden;
        }
        .console-card::after {
          content: "→";
          position: absolute; right: 16px; bottom: 14px;
          color: var(--muted); font-size: 18px;
          transition: color .15s, transform .15s;
        }
        .console-card:hover {
          border-color: var(--accent-ring);
          background: var(--bg-elev-2);
        }
        .console-card:hover::after {
          color: var(--accent); transform: translateX(3px);
        }
        .console-card__no {
          font-size: 11px; font-weight: 700; letter-spacing: 0.08em;
          color: var(--accent); text-transform: uppercase;
          margin-bottom: 8px;
        }
        .console-card__title { font-size: 18px; font-weight: 700; margin-bottom: 6px; }
        .console-card__desc { font-size: 13px; color: var(--fg-dim); line-height: 1.6; }
        .console-card--muted .console-card__no { color: var(--muted); }
      `}</style>
    </section>
  );
}

export default ConsoleHome;
