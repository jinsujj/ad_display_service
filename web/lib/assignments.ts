/**
 * 관리자 웹이 사용하는 디바이스-식당 할당 API 표면.
 *
 * 와이어 계약(Spring Boot 백엔드, AC 40202 형제 Sub-AC 소관):
 *
 *   POST /api/devices/{id}/assignment   (첫 활성 할당 생성)
 *   PUT  /api/devices/{id}/assignment   (다른 식당으로 재매핑)
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
 * 재매핑 호출은 데모 시나리오 #3의 SSE 기반 진입점: 성공한 PUT은 백엔드가
 * 디바이스로 MAPPING_CHANGED 이벤트를 푸시하도록 트리거하며, 플레이어
 * 페이지가 수 초 내에 플레이리스트를 교체하게 한다.
 *
 * 관리자 UI는 해당 흐름의 사용자 측 트리거다. 이 모듈은 요청을 보내는 얇은
 * TypeScript 어댑터다 — 드롭다운 컴포넌트(AC 40202, Sub-AC 2)가 운영자가
 * 대상을 고르고 "Assign"을 누를 때 [assignDeviceToRestaurant]를 호출한다.
 */

import { apiFetch } from "./api";

/** 저장된 할당 행 — 백엔드 `AssignmentResponse`와 동일. */
export interface DeviceAssignmentResult {
  assignmentId: string;
  deviceId: string;
  restaurantId: string;
  assignedAt: string;
  active: boolean;
}

/**
 * 디바이스를 식당에 재할당한다. PUT 시맨틱을 사용하여 호출은 멱등하며,
 * 최초 할당과 이후 재매핑 모두에 동작한다 — 디바이스에 이미 활성 할당이
 * 있으면 백엔드의 `DeviceAssignmentService`가 생성 케이스를 업데이트로
 * 일원화한다.
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
