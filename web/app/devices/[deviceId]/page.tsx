/**
 * 디바이스 상세 페이지 (`/devices/[deviceId]`).
 *
 * Server shell — AuthGuard 와 페이지 chrome 만 담당, fetch + 렌더링은
 * DeviceDetailClient 에 위임.
 */

import Link from "next/link";

import { AuthGuard } from "@/components/AuthGuard";
import { DeviceDetailClient } from "@/components/DeviceDetailClient";

export const dynamic = "force-dynamic";

export const metadata = {
  title: "디바이스 상세 · AdSignage 어드민",
};

interface Props {
  params: { deviceId: string };
}

export default function DeviceDetailPage({ params }: Props) {
  return (
    <AuthGuard requireRole="OPERATOR">
      <section>
        <div className="page-header">
          <div>
            <h1>디바이스 상세</h1>
            <div className="subtitle">
              디바이스 정보와 거쳐 온 음식점 매핑 이력을 한눈에 확인합니다.
            </div>
          </div>
          <Link href="/devices" className="btn">
            ← 디바이스 목록으로
          </Link>
        </div>

        <DeviceDetailClient deviceId={params.deviceId} />
      </section>
    </AuthGuard>
  );
}
