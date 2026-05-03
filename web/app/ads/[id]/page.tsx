/**
 * 광고 상세/편집 페이지 (`/ads/[id]`).
 *
 * AC 3, Sub-AC 3:
 *   "Build Next.js admin schedule form UI with datetime pickers and play
 *    count input on ad detail/edit page."
 *
 * 구현 메모:
 *   - 서버 컴포넌트 셸 — 페이지 크롬, 광고 id 배너, 그리고 실제 인터랙티브
 *     폼인 클라이언트 컴포넌트 [AdScheduleForm]을 렌더링한다. 셸 자체는
 *     JS 없이 렌더링되지만, 폼은 컨트롤드 입력을 가진 클라이언트
 *     컴포넌트로 하이드레이트된다.
 *   - 아직 GET /api/ads/{id} 엔드포인트가 없으므로(Sub-AC 3 범위 밖 —
 *     PUT/PATCH /schedule 동사만 존재), 페이지는 현재 스케줄을 사전
 *     fetch하지 *않는다*. 폼은 비어 시작하고 완전한 교체를 제출하며,
 *     이는 `AdController.putSchedule`에 문서화된 PUT 시맨틱과 일치한다.
 *   - 추후 백엔드가 단일 광고 read 엔드포인트를 노출하면 이 페이지는
 *     서버 측 fetch 후 [AdScheduleForm.initialValues]로 값을 전달해야
 *     하며 — 폼은 이미 그 prop을 와이어업 해두었다.
 *   - `dynamic = "force-dynamic"`이므로 추후 GET 엔드포인트가 추가되면
 *     매 방문마다 stale 스냅샷을 캐싱하지 않고 라이브 데이터를 읽는다.
 */

import Link from "next/link";

import { AdScheduleForm } from "@/components/AdScheduleForm";
import { AuthGuard } from "@/components/AuthGuard";

export const dynamic = "force-dynamic";

export const metadata = {
  title: "Ad schedule · AdSignage Admin",
};

interface AdEditPageProps {
  params: { id: string };
}

export default function AdEditPage({ params }: AdEditPageProps) {
  const adId = params.id;

  return (
    <AuthGuard>
    <section>
      <div className="page-header">
        <div>
          <h1>광고 스케줄 편집</h1>
          <div className="subtitle">
            이 광고의 일일 송출 윈도우(시작/종료 시간)와 목표 송출 횟수를
            수정합니다. 저장 즉시 적용되며 다음 플레이리스트 새로고침에
            반영됩니다.
          </div>
        </div>
        <Link href="/ads" className="btn">
          ← 광고 목록으로
        </Link>
      </div>

      <h2 className="section-heading">광고 정보</h2>
      <div className="ad-id-banner">
        <span className="muted">광고 ID</span>{" "}
        <code className="ad-id-banner__id">{adId}</code>
      </div>

      <h2 className="section-heading" style={{ marginTop: 24 }}>
        스케줄
      </h2>
      <AdScheduleForm adId={adId} />

      <p className="muted" style={{ marginTop: 24 }}>
        Spring Boot 백엔드의 <code>PUT /api/ads/{adId}/schedule</code> 로
        제출됩니다. 이 엔드포인트는 JWT 인증이 필요하므로 먼저 로그인하면{" "}
        <code>localStorage.adsignage_auth_token</code> 에 토큰이 자동
        저장됩니다. 서버 측 교차 검증으로 <code>endTime &gt; startTime</code>{" "}
        과 일일 송출 횟수 <code>1..10000</code> 범위가 강제됩니다.
      </p>
    </section>
    </AuthGuard>
  );
}
