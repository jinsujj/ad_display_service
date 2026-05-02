/**
 * Devices admin page (`/devices`).
 *
 * AC 40201, Sub-AC 1:
 *   "Create devices list page that fetches and displays all devices with
 *    current restaurant mapping from backend API."
 *
 * AC 40203, Sub-AC 3:
 *   "Implement mapping change handler that calls backend API to update
 *    device-to-restaurant mapping and refreshes the list."
 *
 * Implementation notes:
 *   - Server Component shell: fetches `GET /api/devices` and
 *     `GET /api/restaurants` on the server in parallel, so the operator gets
 *     an immediately-rendered table with no client-side loading spinners.
 *   - The interactive table itself is delegated to
 *     [DevicesTableClient], a Client Component that owns the per-row
 *     mapping change handler. After a successful PUT it calls
 *     `router.refresh()`, which re-runs this Server Component and
 *     re-fetches the list — i.e. "refreshes the list" per Sub-AC 3.
 *   - `dynamic = "force-dynamic"` so each refresh hits the live backend
 *     instead of returning a cached snapshot.
 *   - Errors from either endpoint are caught and surfaced inline so the page
 *     stays usable for the rest of the admin workflow even if one of the
 *     APIs is down.
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

  // Fetch devices and restaurants in parallel — they are independent and the
  // page wants both to render the interactive list.
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
    // We can still render the device list without the restaurant dropdown —
    // the operator just won't be able to reassign until the restaurants
    // endpoint comes back. Surface a non-fatal warning.
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
