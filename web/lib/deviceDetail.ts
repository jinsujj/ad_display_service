import { apiFetch } from "./api";

/**
 * `GET /api/devices/{deviceId}` 응답.
 *
 * - currentAssignment: 현재 활성 매핑(없으면 null).
 * - history: 활성/비활성 모든 매핑 행, 최신순. 디바이스가 거쳐 온 음식점 이력.
 */
export interface AssignmentHistoryItem {
  assignmentId: string;
  restaurantId: string;
  restaurantName: string;
  address: string | null;
  assignedAt: string;
  active: boolean;
}

export interface DeviceDetailResponse {
  deviceId: string;
  deviceName: string;
  registeredAt: string;
  lastSeenAt: string | null;
  currentAssignment: AssignmentHistoryItem | null;
  history: AssignmentHistoryItem[];
}

export async function getDeviceDetail(deviceId: string): Promise<DeviceDetailResponse> {
  if (!deviceId) throw new Error("deviceId is required");
  return apiFetch<DeviceDetailResponse>(
    `/api/devices/${encodeURIComponent(deviceId)}`,
    { method: "GET" },
  );
}
