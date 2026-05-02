/**
 * 광고 인덱스 페이지 (`/ads`).
 *
 * AC 3, Sub-AC 3 — 스케줄 폼의 진입점.
 *
 * 백엔드는 (아직) `GET /api/ads` 목록 엔드포인트를 노출하지 않는다 —
 * AC 3 범위에는 `PUT/PATCH /api/ads/{id}/schedule`만 있다. 형제 AC가 목록
 * 엔드포인트를 추가할 때까지 이 페이지는 의도적으로 최소한의 "id로 광고
 * 찾기" 셈:
 *
 *   - 작은 폼에서 광고 id(UUID)를 받음,
 *   - 제출 시 실제 스케줄 에디터([AdScheduleForm])가 사는 `/ads/{id}`로
 *     이동,
 *   - id가 해커톤 흐름에서 어디서 오는지 설명하는 인라인 노트를 렌더(광고주는
 *     광고 생성 시 받음 — 데모에서는 광고 생성 응답이나 H2 콘솔에서 붙여
 *     넣기).
 *
 * GET /api/ads 엔드포인트가 생기면 이 페이지는 호출 광고주가 소유한 모든
 * 광고를 행별 "Edit schedule" 링크와 함께 나열하는 서버 컴포넌트로 업그레이드
 * 되어야 한다 — `/devices`와 `/videos`가 이미 사용하는 패턴과 일치하게.
 */

import { AdLookupForm } from "@/components/AdLookupForm";

export const dynamic = "force-dynamic";

export const metadata = {
  title: "Ads · AdSignage Admin",
};

export default function AdsIndexPage() {
  return (
    <section>
      <div className="page-header">
        <div>
          <h1>Ads</h1>
          <div className="subtitle">
            Find an ad by id and edit its daily playback schedule.
          </div>
        </div>
      </div>

      <div className="notice" role="note">
        <strong>Hackathon scope note.</strong> The backend currently exposes
        only <code>PUT/PATCH /api/ads/&#123;id&#125;/schedule</code> (no list
        endpoint yet). Paste the ad UUID below to jump to its schedule
        editor — the create/list endpoints land in a sibling AC.
      </div>

      <h2 className="section-heading">Open an ad</h2>
      <AdLookupForm />
    </section>
  );
}
