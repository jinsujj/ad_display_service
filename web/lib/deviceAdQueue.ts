/**
 * 디바이스 ↔ 광고 큐 관리 API 클라이언트.
 *
 * 운영자는 "이 디바이스에 어떤 광고들을 송출할지" 를 명시적으로 큐에 담는다.
 * 백엔드 와이어 계약은 `DeviceAdQueueController.kt` 참조.
 */

import { apiFetch } from "./api";
import type { AdStatus } from "./ads";

export interface QueuedAdItem {
  adId: string;
  title: string;
  videoFilename: string;
  /** "HH:mm" */
  startTime: string;
  /** "HH:mm" */
  endTime: string;
  dailyPlayCount: number;
  /** "YYYY-MM-DD" */
  campaignStartDate: string;
  /** "YYYY-MM-DD" */
  campaignEndDate: string;
  status: AdStatus;
  /** ISO-8601 instant — 큐에 담긴 시각. */
  addedAt: string;
}

export interface AddAdToQueueResponse {
  deviceId: string;
  adId: string;
  addedAt: string;
  /** true 면 신규 추가, false 면 이미 큐에 있어 멱등 no-op. */
  created: boolean;
}

/** `GET /api/devices/{deviceId}/ads` */
export async function listDeviceQueue(deviceId: string): Promise<QueuedAdItem[]> {
  if (!deviceId) throw new Error("deviceId is required");
  return apiFetch<QueuedAdItem[]>(
    `/api/devices/${encodeURIComponent(deviceId)}/ads`,
    { method: "GET" },
  );
}

/** `POST /api/devices/{deviceId}/ads` — 광고를 디바이스 큐에 추가 (멱등). */
export async function addAdToQueue(
  deviceId: string,
  adId: string,
): Promise<AddAdToQueueResponse> {
  if (!deviceId) throw new Error("deviceId is required");
  if (!adId) throw new Error("adId is required");
  return apiFetch<AddAdToQueueResponse>(
    `/api/devices/${encodeURIComponent(deviceId)}/ads`,
    {
      method: "POST",
      body: { adId },
    },
  );
}

/** `DELETE /api/devices/{deviceId}/ads/{adId}` — 큐에서 제거 (멱등). */
export async function removeAdFromQueue(
  deviceId: string,
  adId: string,
): Promise<void> {
  if (!deviceId) throw new Error("deviceId is required");
  if (!adId) throw new Error("adId is required");
  await apiFetch<undefined>(
    `/api/devices/${encodeURIComponent(deviceId)}/ads/${encodeURIComponent(adId)}`,
    { method: "DELETE" },
  );
}
