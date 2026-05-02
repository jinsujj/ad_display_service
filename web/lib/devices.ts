/**
 * Devices API surface used by the admin web.
 *
 * Wire contract (Spring Boot backend):
 *
 *   GET /api/devices
 *     -> 200 OK, application/json
 *        [
 *          {
 *            "deviceId":          string  (UUID),
 *            "deviceName":        string,
 *            "registeredAt":      string  (ISO-8601 instant),
 *            "currentRestaurant": {                  // nullable
 *              "restaurantId":   string,
 *              "restaurantName": string,
 *              "address":        string?,           // optional
 *              "assignedAt":     string  (ISO-8601)
 *            } | null
 *          },
 *          ...
 *        ]
 *
 * The shape mirrors the AdSignageSystem ontology (device_*, restaurant_*,
 * device_restaurant_id is represented as the nested currentRestaurant
 * object). The list endpoint is the data source for the admin Devices page
 * (Sub-AC 1 of AC 40201). The endpoint is implemented in a sibling sub-AC;
 * this module is tolerant of the endpoint not yet existing — the page will
 * surface the error to the operator instead of crashing.
 */

import { apiFetch } from "./api";

/** Restaurant currently mapped to a device, or null if unmapped. */
export interface CurrentRestaurant {
  restaurantId: string;
  restaurantName: string;
  address?: string | null;
  assignedAt: string;
}

/** Wire shape for a single device row in the admin Devices list. */
export interface DeviceListItem {
  deviceId: string;
  deviceName: string;
  registeredAt: string;
  currentRestaurant: CurrentRestaurant | null;
}

/** Tolerant variant for legacy/alternative backend shapes. */
type RawDevice = Partial<DeviceListItem> & {
  id?: string;
  name?: string;
  restaurantId?: string | null;
  restaurantName?: string | null;
  restaurant?:
    | (Partial<CurrentRestaurant> & {
        id?: string;
        name?: string;
      })
    | null;
  assignment?:
    | (Partial<CurrentRestaurant> & {
        id?: string;
        name?: string;
      })
    | null;
};

/**
 * Normalise whatever the backend returns into the canonical [DeviceListItem]
 * shape. The JPA layer is being built in parallel with this UI; tolerating a
 * couple of common alias spellings (`id`/`name`, nested `restaurant`/`assignment`)
 * lets us ship the page without blocking on a single agreed-upon JSON contract.
 */
function normaliseDevice(raw: RawDevice): DeviceListItem {
  const deviceId = raw.deviceId ?? raw.id ?? "";
  const deviceName = raw.deviceName ?? raw.name ?? "";
  const registeredAt = raw.registeredAt ?? "";

  let current: CurrentRestaurant | null = null;
  if (raw.currentRestaurant) {
    current = {
      restaurantId: raw.currentRestaurant.restaurantId ?? "",
      restaurantName: raw.currentRestaurant.restaurantName ?? "",
      address: raw.currentRestaurant.address ?? null,
      assignedAt: raw.currentRestaurant.assignedAt ?? "",
    };
  } else if (raw.restaurant) {
    current = {
      restaurantId: raw.restaurant.restaurantId ?? raw.restaurant.id ?? "",
      restaurantName: raw.restaurant.restaurantName ?? raw.restaurant.name ?? "",
      address: raw.restaurant.address ?? null,
      assignedAt: raw.restaurant.assignedAt ?? "",
    };
  } else if (raw.assignment) {
    current = {
      restaurantId: raw.assignment.restaurantId ?? raw.assignment.id ?? "",
      restaurantName: raw.assignment.restaurantName ?? raw.assignment.name ?? "",
      address: raw.assignment.address ?? null,
      assignedAt: raw.assignment.assignedAt ?? "",
    };
  } else if (raw.restaurantId) {
    current = {
      restaurantId: raw.restaurantId,
      restaurantName: raw.restaurantName ?? "",
      address: null,
      assignedAt: "",
    };
  }

  return {
    deviceId,
    deviceName,
    registeredAt,
    currentRestaurant: current,
  };
}

/**
 * Fetches the full devices list from the backend.
 *
 * Returns a normalised [DeviceListItem] array. Throws [ApiError] from
 * `lib/api.ts` if the backend responds with non-2xx — callers (e.g. the
 * Devices page) catch this and render an inline error notice.
 */
export async function listDevices(): Promise<DeviceListItem[]> {
  const raw = await apiFetch<RawDevice[] | { items?: RawDevice[] }>("/api/devices");

  // Some Spring controllers wrap collections in `{ items: [...] }`. Accept both.
  const items = Array.isArray(raw) ? raw : Array.isArray(raw?.items) ? raw.items : [];
  return items.map(normaliseDevice);
}

/* -------------------------------------------------------------- PATCH device */

/**
 * Wire shape for `PATCH /api/devices/{deviceId}` — see
 * `backend/.../assignment/dto/AssignmentDtos.kt :: UpdateDeviceRequest`.
 *
 * Every field is optional. The backend treats an absent key as "leave alone";
 * present-but-blank is rejected at validation time. For the remap flow only
 * `restaurantId` is used; `screenName` / `groupName` are forward-compat
 * placeholders for upcoming sub-ACs.
 */
export interface DevicePatchRequest {
  restaurantId?: string;
  screenName?: string;
  groupName?: string;
}

/**
 * Wire shape for the response of `PATCH /api/devices/{deviceId}` — mirrors
 * `backend/.../assignment/dto/AssignmentDtos.kt :: DeviceResponse`.
 *
 * `restaurantId` / `assignmentId` / `assignedAt` may be null when the device
 * has no active assignment after the patch (e.g. a future "unassign" path).
 * For a `restaurantId`-bearing PATCH they are always populated, so the admin
 * list UI can use them to drive the optimistic row update + success notice.
 */
export interface DevicePatchResponse {
  deviceId: string;
  restaurantId: string | null;
  assignmentId: string | null;
  assignedAt: string | null;
  screenName?: string | null;
  groupName?: string | null;
}

/**
 * PATCH /api/devices/{deviceId} — generic partial-update of a device record.
 *
 * This is the umbrella route the admin device-list remap UI hits when the
 * operator confirms a new restaurant target (AC 9, Sub-AC 3). The same call
 * absorbs future device-level fields (screenName, groupName, …) without
 * fanning out into per-field endpoints.
 *
 * On success: returns the post-patch [DevicePatchResponse], including the
 * resolved active assignment so the caller can update its row optimistically
 * and dismiss the modal in a single round trip.
 *
 * On failure: throws [ApiError] from `lib/api.ts`. The admin list UI catches
 * this and surfaces it inline (`Remap failed: HTTP 4xx (…)`) without closing
 * the modal, so the operator can correct the input and retry.
 */
export async function patchDevice(
  deviceId: string,
  patch: DevicePatchRequest,
): Promise<DevicePatchResponse> {
  if (!deviceId) throw new Error("deviceId is required");
  if (!patch || (patch.restaurantId === undefined && patch.screenName === undefined && patch.groupName === undefined)) {
    // Mirrors the backend's "PATCH body must include at least one updatable
    // field" 400 — fail fast on the client so we don't generate a useless
    // round-trip.
    throw new Error("patch must include at least one field");
  }

  return apiFetch<DevicePatchResponse>(
    `/api/devices/${encodeURIComponent(deviceId)}`,
    {
      method: "PATCH",
      body: patch,
    },
  );
}
