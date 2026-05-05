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
      <section className="space-y-6">
        <header className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
          <div>
            <h1 className="text-2xl font-semibold tracking-tight">
              디바이스 상세
            </h1>
            <p className="mt-1 text-sm text-muted-foreground">
              디바이스 정보와 거쳐 온 음식점 매핑 이력을 한눈에 확인합니다.
            </p>
          </div>
          <Link
            href="/devices"
            className="inline-flex h-11 items-center justify-center rounded-md border border-input bg-background px-4 text-sm font-medium transition-colors hover:bg-accent/15 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2"
          >
            ← 디바이스 목록으로
          </Link>
        </header>

        <DeviceDetailClient deviceId={params.deviceId} />
      </section>
    </AuthGuard>
  );
}
