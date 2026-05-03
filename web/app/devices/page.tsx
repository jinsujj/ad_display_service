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
          <h1>디바이스</h1>
          <div className="subtitle">
            등록된 모든 광고판 디바이스와 현재 매핑된 음식점입니다.
            행의 재할당 버튼을 누르면 SSE로 즉시 디바이스에 변경이 전달됩니다.
          </div>
        </div>
      </div>

      {devicesError && (
        <div className="notice notice-error" role="alert">
          백엔드에서 디바이스 목록을 불러오지 못했습니다: {devicesError}
        </div>
      )}

      {!devicesError && restaurantsError && (
        <div className="notice notice-error" role="alert">
          디바이스는 불러왔지만 음식점 목록이 사용 불가능합니다:{" "}
          {restaurantsError}. <code>/api/restaurants</code> 엔드포인트가
          복구되기 전까지 재할당이 비활성화됩니다.
        </div>
      )}

      {!devicesError && devices.length === 0 && (
        <div className="empty-state">
          아직 등록된 디바이스가 없습니다. 광고판 디바이스를 이 백엔드를 향해
          부팅한 뒤 페이지를 새로고침하세요.
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
  return "알 수 없는 오류";
}
