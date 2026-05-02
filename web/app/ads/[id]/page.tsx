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
    <section>
      <div className="page-header">
        <div>
          <h1>Edit ad schedule</h1>
          <div className="subtitle">
            Update the daily playback window and target play count for this
            ad. Changes are saved immediately on submit and the next playlist
            refresh will pick them up.
          </div>
        </div>
        <Link href="/ads" className="btn">
          ← Back to ads
        </Link>
      </div>

      <h2 className="section-heading">Ad reference</h2>
      <div className="ad-id-banner">
        <span className="muted">Ad id</span>{" "}
        <code className="ad-id-banner__id">{adId}</code>
      </div>

      <h2 className="section-heading" style={{ marginTop: 24 }}>
        Schedule
      </h2>
      <AdScheduleForm adId={adId} />

      <p className="muted" style={{ marginTop: 24 }}>
        Submits to <code>PUT /api/ads/{adId}/schedule</code> on the Spring
        Boot backend. The endpoint is JWT-authenticated — store a token under{" "}
        <code>localStorage.adsignage_auth_token</code> (the login UI lands in
        a sibling AC). Server-side cross-field validation enforces{" "}
        <code>endTime &gt; startTime</code> and a daily count of{" "}
        <code>1..10000</code>.
      </p>
    </section>
  );
}
