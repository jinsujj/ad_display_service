/**
 * Device-to-restaurant assignment API surface used by the admin web.
 *
 * Wire contract (Spring Boot backend, owned by AC 40202 sibling sub-ACs):
 *
 *   POST /api/devices/{id}/assignment   (create first active assignment)
 *   PUT  /api/devices/{id}/assignment   (remap to a different restaurant)
 *   body: { "restaurantId": string }
 *   -> 200/201 application/json
 *      {
 *        "assignmentId": string,
 *        "deviceId":     string,
 *        "restaurantId": string,
 *        "assignedAt":   string  (ISO-8601 instant),
 *        "active":       boolean
 *      }
 *
 * The remap call is the SSE-driven entry point for demo scenario #3: a
 * successful PUT triggers the backend to push a MAPPING_CHANGED event to the
 * device, which causes the player page to swap its playlist within seconds.
 *
 * The admin UI is the human-facing trigger for that flow. This module is the
 * thin TypeScript shim that issues the request — the dropdown component (AC
 * 40202, Sub-AC 2) calls [assignDeviceToRestaurant] when the operator picks a
 * target and clicks "Assign".
 */

import { apiFetch } from "./api";

/** Persisted assignment row — mirrors backend `AssignmentResponse`. */
export interface DeviceAssignmentResult {
  assignmentId: string;
  deviceId: string;
  restaurantId: string;
  assignedAt: string;
  active: boolean;
}

/**
 * Reassigns a device to a restaurant. Uses PUT semantics so the call is
 * idempotent and works for both first-time assignment and subsequent remaps —
 * the backend's `DeviceAssignmentService` collapses the create case to update
 * if the device already has an active assignment.
 */
export async function assignDeviceToRestaurant(
  deviceId: string,
  restaurantId: string,
): Promise<DeviceAssignmentResult> {
  if (!deviceId) throw new Error("deviceId is required");
  if (!restaurantId) throw new Error("restaurantId is required");

  return apiFetch<DeviceAssignmentResult>(
    `/api/devices/${encodeURIComponent(deviceId)}/assignment`,
    {
      method: "PUT",
      body: { restaurantId },
    },
  );
}
