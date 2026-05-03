/**
 * 디바이스 어드민 페이지 (`/devices`).
 *
 * - `MyDevicesList` (클라이언트 컴포넌트) 가 GET /api/devices 와
 *   GET /api/restaurants 를 마운트 후 호출한다. localStorage 의 JWT 가
 *   자동으로 첨부됨.
 * - `AuthGuard` 가 미인증 사용자를 /login 으로 보냄.
 *
 * 이전엔 서버 컴포넌트에서 SSR fetch 했는데, 그 단계엔 localStorage 가 없어
 * 토큰을 못 실어 401 이 떨어졌다 — 그래서 클라이언트 사이드로 옮김.
 */

import { AuthGuard } from "@/components/AuthGuard";
import { MyDevicesList } from "@/components/MyDevicesList";

export const dynamic = "force-dynamic";

export const metadata = {
  title: "디바이스 · AdSignage 어드민",
};

export default function DevicesPage() {
  return (
    <AuthGuard>
      <section>
        <div className="page-header">
          <div>
            <h1>디바이스</h1>
            <div className="subtitle">
              등록된 모든 광고판 디바이스와 현재 매핑된 음식점입니다.
              행의 재할당 버튼을 누르면 SSE로 즉시 디바이스에 변경이 전달됩니다.
            </div>
          </div>
        </div>

        <MyDevicesList />
      </section>
    </AuthGuard>
  );
}
