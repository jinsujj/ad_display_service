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
import { notifyDataChanged } from "./dataEvents";

/** 디바이스에 현재 매핑된 식당. 매핑이 없으면 null. */
export interface CurrentRestaurant {
  restaurantId: string;
  restaurantName: string;
  address?: string | null;
  assignedAt: string;
}

/**
 * 디바이스 큐에 담긴 광고의 모니터링 썸네일용 요약.
 * `videoFilename` 으로 `/api/videos/{filename}` Range 엔드포인트에 접근.
 */
export interface QueuedAdSummary {
  adId: string;
  title: string;
  videoFilename: string;
  /** SCHEDULED / ACTIVE / EXPIRED — 캠페인 기간 기반 상태 라벨. */
  status: "SCHEDULED" | "ACTIVE" | "EXPIRED";
}

/**
 * 디바이스가 *지금 실제로 송출 중* 인 광고. 서버가 가장 최근 STARTED
 * 이벤트로 결정하므로 클라이언트의 큐 시뮬레이션이 아닌 서버 진실.
 * 오프라인이거나 최근 활동이 없으면 null.
 */
export interface CurrentAd {
  adId: string;
  title: string;
  videoFilename: string;
  /** 디바이스가 광고 시작을 보고한 시각 (ISO-8601). */
  startedAt: string;
}

/** 관리자 Devices 리스트의 단일 디바이스 행에 대한 와이어 형태. */
export interface DeviceListItem {
  deviceId: string;
  deviceName: string;
  registeredAt: string;
  /** 마지막 활동(register / play-event) 시각. ISO-8601. null 이면 미관측. */
  lastSeenAt: string | null;
  currentRestaurant: CurrentRestaurant | null;
  /**
   * 이 디바이스 큐에 담긴 광고 요약 — 디바이스 상세 페이지의 큐 카운트와
   * 모니터 카드 보조 정보에 사용.
   */
  queuedAds: QueuedAdSummary[];
  /** 디바이스가 지금 살아있는가? (SSE 연결 OR 최근 play-event) */
  online: boolean;
  /** 지금 송출 중인 광고. 오프라인이거나 최근 STARTED 없으면 null. */
  currentAd: CurrentAd | null;
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

  const rawQueued = (raw as { queuedAds?: unknown }).queuedAds;
  const queuedAds: QueuedAdSummary[] = Array.isArray(rawQueued)
    ? rawQueued
        .map((q): QueuedAdSummary | null => {
          if (!q || typeof q !== "object") return null;
          const o = q as Record<string, unknown>;
          const adId = typeof o.adId === "string" ? o.adId : "";
          const videoFilename =
            typeof o.videoFilename === "string" ? o.videoFilename : "";
          if (!adId || !videoFilename) return null;
          const statusRaw = typeof o.status === "string" ? o.status : "ACTIVE";
          const status =
            statusRaw === "SCHEDULED" ||
            statusRaw === "ACTIVE" ||
            statusRaw === "EXPIRED"
              ? statusRaw
              : "ACTIVE";
          return {
            adId,
            title: typeof o.title === "string" ? o.title : "",
            videoFilename,
            status,
          };
        })
        .filter((q): q is QueuedAdSummary => q !== null)
    : [];

  // currentAd 안전 파싱.
  const rawCurrent = (raw as { currentAd?: unknown }).currentAd;
  let currentAd: CurrentAd | null = null;
  if (rawCurrent && typeof rawCurrent === "object") {
    const ca = rawCurrent as Record<string, unknown>;
    const adId = typeof ca.adId === "string" ? ca.adId : "";
    const videoFilename =
      typeof ca.videoFilename === "string" ? ca.videoFilename : "";
    if (adId && videoFilename) {
      currentAd = {
        adId,
        title: typeof ca.title === "string" ? ca.title : "",
        videoFilename,
        startedAt: typeof ca.startedAt === "string" ? ca.startedAt : "",
      };
    }
  }

  const online = (raw as { online?: unknown }).online === true;
  const lastSeenAtRaw = (raw as { lastSeenAt?: unknown }).lastSeenAt;
  const lastSeenAt =
    typeof lastSeenAtRaw === "string" && lastSeenAtRaw ? lastSeenAtRaw : null;

  return {
    deviceId,
    deviceName,
    registeredAt,
    lastSeenAt,
    currentRestaurant: current,
    queuedAds,
    online,
    currentAd,
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
  /** 디바이스 별칭. 1..255 자, trim 후 빈 문자열 불가. */
  deviceName?: string;
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
  if (!patch || (patch.restaurantId === undefined && patch.screenName === undefined && patch.groupName === undefined && patch.deviceName === undefined)) {
    // 백엔드의 "PATCH 본문에는 업데이트 가능한 필드가 최소 하나 포함되어야
    // 함" 400 응답을 그대로 반영 — 무의미한 왕복을 막기 위해 클라이언트에서
    // 즉시 실패시킨다.
    throw new Error("patch must include at least one field");
  }

  const result = await apiFetch<DevicePatchResponse>(
    `/api/devices/${encodeURIComponent(deviceId)}`,
    {
      method: "PATCH",
      body: patch,
    },
  );
  notifyDataChanged("device");
  return result;
}

/**
 * `DELETE /api/devices/{deviceId}` — 어드민이 디바이스 행을 제거.
 *
 * 디바이스 행과 모든 매핑 이력이 삭제된다. 디바이스 앱이 다시 켜지면
 * 자기 device_id 로 멱등 register 를 다시 호출해 새 행이 생성된다.
 *
 * 분실/교체/테스트 정리에 사용. 운영자는 confirm 후 호출.
 */
export async function deleteDevice(deviceId: string): Promise<void> {
  if (!deviceId) throw new Error("deviceId is required");
  await apiFetch<undefined>(
    `/api/devices/${encodeURIComponent(deviceId)}`,
    { method: "DELETE" },
  );
  notifyDataChanged("device");
}
