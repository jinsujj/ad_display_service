/**
 * Restaurants API surface used by the admin web.
 *
 * Wire contract (Spring Boot backend):
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
 * Used by the restaurant assignment UI (AC 40202, Sub-AC 2): the dropdown lists
 * every restaurant the operator can target for a device remap. The endpoint is
 * being built in a sibling sub-AC, so this module is tolerant of the endpoint
 * not yet existing — the component surfaces the error inline rather than
 * crashing.
 *
 * Tolerated alternative shapes (mirrors `lib/devices.ts` conventions):
 *   - top-level wrapper `{ items: [...] }`
 *   - id/name aliases (`id`, `name`) instead of canonical keys
 */

import { apiFetch } from "./api";

/** Wire shape for a single restaurant in the admin Restaurants list. */
export interface RestaurantListItem {
  restaurantId: string;
  restaurantName: string;
  address?: string | null;
}

/** Tolerant variant for legacy / alternative backend shapes. */
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
 * Fetches the full restaurant list from the backend.
 *
 * Returns a normalised [RestaurantListItem] array. Throws [ApiError] from
 * `lib/api.ts` if the backend responds with non-2xx — callers (the assignment
 * UI) catch this and render an inline error notice.
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
    // Drop rows missing the FK we'd need to assign — defensive against partial
    // backend responses during demo bring-up.
    .filter((r) => r.restaurantId.length > 0);
}
