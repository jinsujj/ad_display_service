/**
 * 디바이스 어드민 페이지 (`/devices`).
 *
 * AC 40201, Sub-AC 1:
 *   "Create devices list page that fetches and displays all devices with
 *    current restaurant mapping from backend API."
 *
 * AC 40203, Sub-AC 3:
 *   "Implement mapping change handler that calls backend API to update
 *    device-to-restaurant mapping and refreshes the list."
 *
 * 구현 메모:
 *   - 서버 컴포넌트 셸: `GET /api/devices`와 `GET /api/restaurants`를
 *     서버에서 병렬로 fetch하므로 운영자는 클라이언트 측 로딩 스피너
 *     없이 즉시 렌더링된 테이블을 본다.
 *   - 인터랙티브 테이블 자체는 행별 매핑 변경 핸들러를 소유하는 클라이언트
 *     컴포넌트 [DevicesTableClient]에 위임된다. 성공한 PUT 후
 *     `router.refresh()`를 호출하여 이 서버 컴포넌트를 재실행하고 목록을
 *     다시 fetch — 즉 Sub-AC 3의 "목록 갱신".
 *   - `dynamic = "force-dynamic"`이므로 매 새로고침이 캐시된 스냅샷이
 *     아닌 라이브 백엔드를 친다.
 *   - 두 엔드포인트의 오류는 모두 캐치되어 인라인으로 노출되므로, 한쪽
 *     API가 다운되어도 페이지가 나머지 어드민 워크플로에서 사용 가능한
 *     상태를 유지한다.
 */

import { ApiError } from "@/lib/api";
import { listDevices, type DeviceListItem } from "@/lib/devices";
import { listRestaurants, type RestaurantListItem } from "@/lib/restaurants";
import { DevicesTableClient } from "@/components/DevicesTableClient";

export const dynamic = "force-dynamic";

export const metadata = {
  title: "Devices · AdSignage Admin",
};

export default async function DevicesPage() {
  let devices: DeviceListItem[] = [];
  let restaurants: RestaurantListItem[] = [];
  let devicesError: string | null = null;
  let restaurantsError: string | null = null;

  // 디바이스와 음식점을 병렬 fetch — 둘은 독립적이며 페이지는 인터랙티브
  // 목록을 렌더링하기 위해 둘 다 필요하다.
  const [devicesResult, restaurantsResult] = await Promise.allSettled([
    listDevices(),
    listRestaurants(),
  ]);

  if (devicesResult.status === "fulfilled") {
    devices = devicesResult.value;
  } else {
    devicesError = describeError(devicesResult.reason);
  }
  if (restaurantsResult.status === "fulfilled") {
    restaurants = restaurantsResult.value;
  } else {
    // 음식점 드롭다운 없이도 디바이스 목록은 렌더링 가능 — 운영자는
    // 음식점 엔드포인트가 복구될 때까지 재할당만 못 할 뿐이다. 비치명적
    // 경고를 노출.
    restaurantsError = describeError(restaurantsResult.reason);
  }

  return (
    <section>
      <div className="page-header">
        <div>
          <h1>Devices</h1>
          <div className="subtitle">
            All registered signage devices and the restaurant each one is
            currently mapped to. Use the per-row Reassign button to remap a
            device — the change is pushed to the device immediately via SSE.
          </div>
        </div>
      </div>

      {devicesError && (
        <div className="notice notice-error" role="alert">
          Failed to load devices from backend: {devicesError}
        </div>
      )}

      {!devicesError && restaurantsError && (
        <div className="notice notice-error" role="alert">
          Devices loaded, but the restaurant list is unavailable:{" "}
          {restaurantsError}. Reassignment is disabled until the
          <code> /api/restaurants </code> endpoint recovers.
        </div>
      )}

      {!devicesError && devices.length === 0 && (
        <div className="empty-state">
          No devices registered yet. Boot a signage device pointing at this
          backend and refresh this page.
        </div>
      )}

      {!devicesError && devices.length > 0 && (
        <DevicesTableClient
          initialDevices={devices}
          restaurants={restaurants}
        />
      )}
    </section>
  );
}

function describeError(err: unknown): string {
  if (err instanceof ApiError) {
    return `HTTP ${err.status} (${err.url})`;
  }
  if (err instanceof Error) return err.message;
  return "unknown error";
}
