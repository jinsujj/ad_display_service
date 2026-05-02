/**
 * 관리자 웹이 사용하는 식당 API 표면.
 *
 * 와이어 계약(Spring Boot 백엔드):
 *
 *   GET /api/restaurants
 *     -> 200 OK, application/json
 *        [
 *          {
 *            "restaurantId": string,           (UUID)
 *            "restaurantName": string,
 *            "address": string?                (optional)
 *          },
 *          ...
 *        ]
 *
 * 식당 할당 UI(AC 40202, Sub-AC 2)에서 사용된다: 드롭다운에는 운영자가 디바이스
 * 재매핑 시 지정할 수 있는 모든 식당이 나열된다. 엔드포인트는 형제 Sub-AC에서
 * 구축 중이므로, 이 모듈은 엔드포인트가 아직 없는 경우에도 견고하다 —
 * 컴포넌트는 크래시 대신 에러를 인라인으로 표시한다.
 *
 * 허용되는 대체 형태(`lib/devices.ts` 규약과 동일):
 *   - 최상위 래퍼 `{ items: [...] }`
 *   - 표준 키 대신 id/name 별칭(`id`, `name`)
 */

import { apiFetch } from "./api";

/** 관리자 Restaurants 리스트의 단일 식당에 대한 와이어 형태. */
export interface RestaurantListItem {
  restaurantId: string;
  restaurantName: string;
  address?: string | null;
}

/** 레거시/대체 백엔드 형태에 관대한 변형. */
type RawRestaurant = Partial<RestaurantListItem> & {
  id?: string;
  name?: string;
};

function normaliseRestaurant(raw: RawRestaurant): RestaurantListItem {
  const restaurantId = raw.restaurantId ?? raw.id ?? "";
  const restaurantName = raw.restaurantName ?? raw.name ?? "";
  const address = raw.address ?? null;
  return { restaurantId, restaurantName, address };
}

/**
 * 백엔드에서 전체 식당 목록을 가져온다.
 *
 * 정규화된 [RestaurantListItem] 배열을 반환한다. 백엔드가 2xx가 아닌 응답을
 * 하면 `lib/api.ts`의 [ApiError]를 throw한다 — 호출자(할당 UI)는 이를 캐치하고
 * 인라인 에러 안내를 렌더링한다.
 */
export async function listRestaurants(): Promise<RestaurantListItem[]> {
  const raw = await apiFetch<RawRestaurant[] | { items?: RawRestaurant[] }>(
    "/api/restaurants",
  );
  const items = Array.isArray(raw)
    ? raw
    : Array.isArray(raw?.items)
      ? raw.items
      : [];
  return items
    .map(normaliseRestaurant)
    // 할당에 필요한 FK가 없는 행은 제거 — 데모 셋업 중 백엔드의 부분 응답에
    // 대한 방어적 처리.
    .filter((r) => r.restaurantId.length > 0);
}
