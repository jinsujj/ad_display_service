/**
 * 관리자 웹이 사용하는 디바이스 API 표면.
 *
 * 와이어 계약(Spring Boot 백엔드):
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
 * 이 형태는 AdSignageSystem 온톨로지(device_*, restaurant_*, device_restaurant_id는
 * 중첩된 currentRestaurant 객체로 표현)와 일치한다. list 엔드포인트는 관리자
 * Devices 페이지(AC 40201의 Sub-AC 1)의 데이터 소스다. 엔드포인트는 형제
 * Sub-AC에서 구현 중이며, 이 모듈은 엔드포인트가 아직 없는 경우에도 견고하다
 * — 페이지는 크래시 대신 에러를 운영자에게 노출한다.
 */

import { apiFetch } from "./api";

/** 디바이스에 현재 매핑된 식당. 매핑이 없으면 null. */
export interface CurrentRestaurant {
  restaurantId: string;
  restaurantName: string;
  address?: string | null;
  assignedAt: string;
}

/** 관리자 Devices 리스트의 단일 디바이스 행에 대한 와이어 형태. */
export interface DeviceListItem {
  deviceId: string;
  deviceName: string;
  registeredAt: string;
  currentRestaurant: CurrentRestaurant | null;
}

/** 레거시/대체 백엔드 형태에 관대한 변형. */
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
 * 백엔드가 반환하는 형태를 표준 [DeviceListItem] 형태로 정규화한다. JPA 계층은
 * 이 UI와 병렬로 구축되고 있으므로, 흔한 별칭 표기 몇 가지(`id`/`name`,
 * 중첩된 `restaurant`/`assignment`)를 허용해 단일 합의된 JSON 계약을 기다리지
 * 않고 페이지를 배포할 수 있게 한다.
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
 * 백엔드에서 전체 디바이스 목록을 가져온다.
 *
 * 정규화된 [DeviceListItem] 배열을 반환한다. 백엔드가 2xx가 아닌 응답을 하면
 * `lib/api.ts`의 [ApiError]를 throw한다 — 호출자(예: Devices 페이지)는 이를
 * 캐치하고 인라인 에러 안내를 렌더링한다.
 */
export async function listDevices(): Promise<DeviceListItem[]> {
  const raw = await apiFetch<RawDevice[] | { items?: RawDevice[] }>("/api/devices");

  // 일부 Spring 컨트롤러는 컬렉션을 `{ items: [...] }`로 감싼다. 둘 다 수용.
  const items = Array.isArray(raw) ? raw : Array.isArray(raw?.items) ? raw.items : [];
  return items.map(normaliseDevice);
}

/* -------------------------------------------------------------- PATCH device */

/**
 * `PATCH /api/devices/{deviceId}`의 와이어 형태 — 참조:
 * `backend/.../assignment/dto/AssignmentDtos.kt :: UpdateDeviceRequest`.
 *
 * 모든 필드는 선택. 백엔드는 키 부재를 "그대로 두기"로 취급하며, 키는 있되
 * 비어 있는 경우는 검증 시 거부된다. 재매핑 흐름에서는 `restaurantId`만
 * 사용한다. `screenName` / `groupName`은 다가올 Sub-AC를 위한 전방 호환
 * 플레이스홀더다.
 */
export interface DevicePatchRequest {
  restaurantId?: string;
  screenName?: string;
  groupName?: string;
}

/**
 * `PATCH /api/devices/{deviceId}` 응답의 와이어 형태 — 다음과 동일:
 * `backend/.../assignment/dto/AssignmentDtos.kt :: DeviceResponse`.
 *
 * 패치 후 디바이스에 활성 할당이 없는 경우(예: 향후의 "unassign" 경로)에는
 * `restaurantId` / `assignmentId` / `assignedAt`이 null일 수 있다.
 * `restaurantId`를 포함하는 PATCH의 경우 항상 값이 채워지므로, 관리자 리스트
 * UI는 낙관적 행 업데이트와 성공 안내 표시에 이 값을 사용할 수 있다.
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
 * PATCH /api/devices/{deviceId} — 디바이스 레코드의 범용 부분 업데이트.
 *
 * 운영자가 새 식당 타깃을 확정할 때(AC 9, Sub-AC 3) 관리자 디바이스 리스트
 * 재매핑 UI가 호출하는 통합 라우트다. 이 호출 한 번이 향후의 디바이스 레벨
 * 필드(screenName, groupName, …)를 필드별 엔드포인트로 분기하지 않고 흡수한다.
 *
 * 성공: 패치 후의 [DevicePatchResponse]를 반환하며, 해소된 활성 할당도
 * 포함되어 있어 호출자가 한 번의 왕복으로 행을 낙관적으로 업데이트하고
 * 모달을 닫을 수 있다.
 *
 * 실패: `lib/api.ts`의 [ApiError]를 throw한다. 관리자 리스트 UI는 이를 잡아
 * 모달을 닫지 않고 인라인으로(`Remap failed: HTTP 4xx (…)`) 노출하므로,
 * 운영자가 입력을 수정하고 재시도할 수 있다.
 */
export async function patchDevice(
  deviceId: string,
  patch: DevicePatchRequest,
): Promise<DevicePatchResponse> {
  if (!deviceId) throw new Error("deviceId is required");
  if (!patch || (patch.restaurantId === undefined && patch.screenName === undefined && patch.groupName === undefined)) {
    // 백엔드의 "PATCH 본문에는 업데이트 가능한 필드가 최소 하나 포함되어야
    // 함" 400 응답을 그대로 반영 — 무의미한 왕복을 막기 위해 클라이언트에서
    // 즉시 실패시킨다.
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
