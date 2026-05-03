"use client";

/**
 * 미인증 사용자에게 보여주는 랜딩(마케팅) 페이지.
 *
 * 서비스 본질을 한 화면 안에 압축:
 *  1. Hero — 한 줄 가치 제안 + 두 개 CTA(회원가입 / 로그인)
 *  2. Why  — 광고주 / 음식점 / 운영 세 관점의 가치 제안
 *  3. How  — 1·2·3 step
 *  4. Foot — 데모/연락 안내
 *
 * 디자인은 어드민과 같은 디자인 토큰을 그대로 쓴다(앰버 + 다크). 별도
 * 페이지가 아니라 같은 layout 안에 있으므로 헤더(브랜드 + 로그인 링크)는
 * 그대로 위에 보인다.
 */

import Link from "next/link";

export function Landing() {
  return (
    <div className="landing">
      {/* ========== Hero ========== */}
      <section className="landing-hero">
        <div className="landing-hero__copy">
          <span className="landing-eyebrow">디지털 사이니지 · B2B</span>
          <h1 className="landing-title">
            손님이 <span className="accent">술 한 잔 따르는 그 자리</span>에서<br />
            광고가 송출됩니다
          </h1>
          <p className="landing-lead">
            전국 음식점의 주류 쇼케이스 냉장고 위에 거치된 디지털 광고판.
            주류 메이커가 직접 영상을 올리고, 시간대·송출 횟수·캠페인 기간을
            정해 배포합니다. 음식점 사장님은 별도 작업 없이 광고 수익의
            일부를 분배받습니다.
          </p>
          <div className="landing-cta">
            <Link href="/signup" className="btn btn-primary">
              광고주 회원가입
            </Link>
            <Link href="/login" className="btn">
              이미 계정이 있으세요? 로그인
            </Link>
          </div>
          <ul className="landing-bullets">
            <li>주류 광고 매체 제한을 우회한 합법적 송출 채널</li>
            <li>실 소비 장소 도달 — 의사결정 직전 노출</li>
            <li>캠페인 기간 종료 시 자동 취소, 송출 증빙 로그 제공</li>
          </ul>
        </div>

        {/* 광고판 모형 — 미세 펄스 인디케이터로 "송출 중" 인상 */}
        <div className="landing-mock" aria-hidden="true">
          <div className="landing-mock__shell">
            <div className="landing-mock__live">
              <span className="landing-mock__dot" /> ON&nbsp;AIR
            </div>
            <div className="landing-mock__screen">
              <div className="landing-mock__brand">진로하이트</div>
              <div className="landing-mock__sub">5월 캠페인 · 11:00 ~ 23:00</div>
              <div className="landing-mock__chip">초기 30 / 일 송출</div>
            </div>
            <div className="landing-mock__stand" />
          </div>
          <div className="landing-mock__caption">
            냉장고 상단 거치 · 10–15&quot; 세로 FHD
          </div>
        </div>
      </section>

      {/* ========== Why ========== */}
      <section className="landing-section">
        <h2 className="landing-h2">왜 이 자리인가</h2>
        <div className="landing-grid">
          <div className="landing-card">
            <div className="landing-card__title">광고주에게</div>
            <p className="landing-card__body">
              주류 광고가 허용되는 매체에 직접 배포. 시간대·요일·기간을
              세팅하면 백엔드가 라운드 로빈으로 디바이스에 분배합니다.
              송출 결과는 이벤트 단위로 집계되어 증빙 가능합니다.
            </p>
          </div>
          <div className="landing-card">
            <div className="landing-card__title">음식점에게</div>
            <p className="landing-card__body">
              디바이스 거치 외 추가 작업 없음. 광고 수익이 정산되어 들어오고,
              매장 인테리어와 어울리는 세로형 디스플레이가 손님 동선에서
              자연스럽게 보입니다.
            </p>
          </div>
          <div className="landing-card">
            <div className="landing-card__title">운영에게</div>
            <p className="landing-card__body">
              어드민에서 디바이스를 음식점에 매핑·재할당. 변경 즉시 SSE로
              디바이스에 푸시되어 새 플레이리스트가 수 초 내 반영됩니다.
              점포 이전·교체에도 대응이 자유롭습니다.
            </p>
          </div>
        </div>
      </section>

      {/* ========== How ========== */}
      <section className="landing-section">
        <h2 className="landing-h2">어떻게 동작하나</h2>
        <ol className="landing-steps">
          <li>
            <span className="landing-step__no">1</span>
            <div>
              <div className="landing-step__title">광고주 회원가입 · MP4 업로드</div>
              <div className="landing-step__sub">
                광고주는 어드민에서 영상을 올리고 시간대·일일 횟수·캠페인
                기간으로 광고를 만듭니다.
              </div>
            </div>
          </li>
          <li>
            <span className="landing-step__no">2</span>
            <div>
              <div className="landing-step__title">디바이스 ↔ 음식점 매핑</div>
              <div className="landing-step__sub">
                운영자가 광고판 디바이스를 음식점에 매핑합니다. 매핑 변경은
                SSE 푸시로 디바이스에 즉시 전달됩니다.
              </div>
            </div>
          </li>
          <li>
            <span className="landing-step__no">3</span>
            <div>
              <div className="landing-step__title">송출 + 자동 취소</div>
              <div className="landing-step__sub">
                광고판은 스케줄 시간대에만 라운드 로빈으로 송출하고,
                캠페인 종료일을 지나면 자동으로 송출이 중단됩니다.
              </div>
            </div>
          </li>
        </ol>
      </section>

      {/* ========== Footer CTA ========== */}
      <section className="landing-final">
        <div>
          <div className="landing-final__h">지금 시작해 보세요</div>
          <div className="landing-final__sub">
            가입 후 영상 한 편을 올리면 5분 안에 첫 광고를 디바이스에
            송출할 수 있습니다.
          </div>
        </div>
        <div className="landing-cta">
          <Link href="/signup" className="btn btn-primary">광고주 회원가입</Link>
          <Link href="/login" className="btn">로그인</Link>
        </div>
      </section>

      {/* ========== styled-jsx (랜딩 전용 스타일) ========== */}
      <style jsx>{`
        .landing { display: flex; flex-direction: column; gap: 56px; padding-bottom: 24px; }
        .landing-eyebrow {
          display: inline-block;
          font-size: 12px; font-weight: 600; letter-spacing: 0.06em;
          text-transform: uppercase; color: var(--accent);
          padding: 4px 10px;
          background: var(--accent-soft);
          border: 1px solid rgba(245, 176, 66, 0.35);
          border-radius: 999px;
          margin-bottom: 18px;
        }

        /* Hero */
        .landing-hero {
          display: grid;
          grid-template-columns: minmax(0, 1.15fr) minmax(0, 0.85fr);
          gap: 40px;
          align-items: center;
          padding-top: 12px;
        }
        @media (max-width: 860px) {
          .landing-hero { grid-template-columns: 1fr; gap: 28px; }
        }
        .landing-title {
          font-size: 38px; font-weight: 800; line-height: 1.18;
          letter-spacing: -0.02em; color: var(--fg);
          margin-bottom: 16px;
        }
        @media (max-width: 720px) { .landing-title { font-size: 28px; } }
        .landing-title .accent { color: var(--accent); }
        .landing-lead {
          color: var(--fg-dim); font-size: 15.5px; line-height: 1.7;
          max-width: 60ch; margin: 0 0 22px;
        }
        .landing-cta { display: flex; gap: 10px; flex-wrap: wrap; align-items: center; }
        :global(.landing-cta .btn) { padding: 11px 18px; font-size: 14px; }
        :global(.landing-cta .btn-primary) {
          background: var(--accent); color: #1a120a; border-color: var(--accent);
          font-weight: 700;
        }
        :global(.landing-cta .btn-primary:hover) {
          background: var(--accent-hi); border-color: var(--accent-hi); color: #1a120a;
        }

        .landing-bullets {
          margin: 26px 0 0; padding: 0; list-style: none;
          display: flex; flex-direction: column; gap: 8px;
          color: var(--fg-dim); font-size: 13.5px;
        }
        .landing-bullets li {
          padding-left: 22px; position: relative;
        }
        .landing-bullets li::before {
          content: "✓"; position: absolute; left: 0; top: 0;
          color: var(--accent); font-weight: 700;
        }

        /* Hero mock */
        .landing-mock {
          display: flex; flex-direction: column; align-items: center; gap: 12px;
        }
        .landing-mock__shell {
          position: relative;
          width: min(280px, 100%);
          aspect-ratio: 9 / 16;
          background: linear-gradient(180deg, #14171c 0%, #0d1014 100%);
          border: 1px solid var(--border);
          border-radius: 18px;
          padding: 18px;
          display: flex; flex-direction: column;
          justify-content: center;
          box-shadow: 0 30px 60px -20px rgba(0,0,0,0.6),
                      0 0 0 1px rgba(245, 176, 66, 0.06);
        }
        .landing-mock__shell::after {
          /* 광고판 화면 가장자리 글로우 */
          content: ""; position: absolute; inset: 8px; border-radius: 12px;
          background:
            radial-gradient(ellipse at 50% 0%, rgba(245,176,66,0.08), transparent 60%);
          pointer-events: none;
        }
        .landing-mock__live {
          position: absolute; top: 14px; left: 14px;
          display: inline-flex; align-items: center; gap: 6px;
          font-size: 10px; font-weight: 700; letter-spacing: 0.1em;
          color: #ff6b6b;
          background: rgba(255, 107, 107, 0.12);
          padding: 3px 8px; border-radius: 999px;
          border: 1px solid rgba(255, 107, 107, 0.35);
        }
        .landing-mock__dot {
          width: 6px; height: 6px; border-radius: 999px; background: #ff6b6b;
          box-shadow: 0 0 0 4px rgba(255,107,107,0.18);
          animation: live-pulse 1.6s ease-in-out infinite;
        }
        @keyframes live-pulse {
          0%, 100% { opacity: 1; transform: scale(1); }
          50% { opacity: 0.55; transform: scale(0.85); }
        }
        .landing-mock__screen { text-align: center; }
        .landing-mock__brand {
          font-size: 28px; font-weight: 800; letter-spacing: -0.02em;
          color: var(--accent); text-shadow: 0 0 20px rgba(245,176,66,0.35);
        }
        .landing-mock__sub {
          margin-top: 6px; color: var(--fg-dim); font-size: 12px;
        }
        .landing-mock__chip {
          margin-top: 14px;
          display: inline-block;
          padding: 5px 10px; border-radius: 999px;
          background: rgba(255,255,255,0.05);
          color: var(--fg);
          font-size: 11.5px; font-weight: 600;
          border: 1px solid var(--border-soft);
        }
        .landing-mock__stand {
          position: absolute; bottom: -10px; left: 50%; transform: translateX(-50%);
          width: 60%; height: 6px; border-radius: 0 0 6px 6px;
          background: linear-gradient(180deg, #1f242c, #0b0d10);
          border-top: 1px solid var(--border);
        }
        .landing-mock__caption { color: var(--muted); font-size: 12px; margin-top: 16px; }

        /* Sections */
        .landing-section { }
        .landing-h2 {
          font-size: 18px; font-weight: 700;
          color: var(--fg); margin-bottom: 18px;
          letter-spacing: -0.01em;
        }
        .landing-grid {
          display: grid;
          grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
          gap: 14px;
        }
        .landing-card {
          background: var(--bg-elev);
          border: 1px solid var(--border);
          border-radius: var(--radius);
          padding: 20px;
        }
        .landing-card__title {
          font-size: 11.5px; font-weight: 700; letter-spacing: 0.06em;
          text-transform: uppercase; color: var(--accent); margin-bottom: 10px;
        }
        .landing-card__body { color: var(--fg-dim); font-size: 13.5px; line-height: 1.7; margin: 0; }

        .landing-steps {
          list-style: none; padding: 0; margin: 0;
          display: flex; flex-direction: column; gap: 10px;
        }
        .landing-steps li {
          display: flex; gap: 16px; align-items: flex-start;
          background: var(--bg-elev); border: 1px solid var(--border);
          border-radius: var(--radius); padding: 16px 18px;
        }
        .landing-step__no {
          width: 28px; height: 28px; flex: 0 0 28px;
          border-radius: 999px;
          background: var(--accent-soft); color: var(--accent);
          display: inline-flex; align-items: center; justify-content: center;
          font-weight: 800; font-size: 13px;
        }
        .landing-step__title { font-weight: 600; color: var(--fg); margin-bottom: 4px; }
        .landing-step__sub { color: var(--fg-dim); font-size: 13.5px; line-height: 1.6; }

        /* Final CTA */
        .landing-final {
          display: flex; align-items: center; justify-content: space-between;
          gap: 20px; flex-wrap: wrap;
          padding: 24px;
          background:
            linear-gradient(135deg, rgba(245,176,66,0.10), rgba(245,176,66,0)),
            var(--bg-elev);
          border: 1px solid rgba(245, 176, 66, 0.25);
          border-radius: var(--radius-lg);
        }
        .landing-final__h { font-size: 17px; font-weight: 700; color: var(--fg); }
        .landing-final__sub { color: var(--fg-dim); font-size: 13.5px; margin-top: 4px; max-width: 56ch; }
      `}</style>
    </div>
  );
}

export default Landing;
