"use client";

/**
 * 음식점 할당 UI 컴포넌트 (AC 40202, Sub-AC 2).
 *
 * 목표:
 *   "Build restaurant assignment UI component with dropdown/selector to choose
 *    target restaurant for a device."
 *
 * 이 컴포넌트가 하는 일:
 *   1. 마운트 시 [listRestaurants]를 통해 전체 음식점 목록을 fetch.
 *   2. 운영자가 주어진 [deviceId]에 대해 대상 음식점을 고를 수 있도록
 *      네이티브 <select> 드롭다운 렌더. 네이티브 select(커스텀 위젯이 아님)
 *      를 쓰는 이유: (a) 해커톤 스택에서 추가 의존성 없이 동작하고
 *      (b) 완전히 키보드 접근 가능, 모바일 친화, 그리고 운영자가 바 뒤에서
 *      쓸 수도 있는 터치 디바이스에서 긴 목록 가상화를 무료로 제공.
 *   3. (알려진 경우) 현재 할당을 보여줘서 운영자가 변경 전에 무엇을
 *      변경하려는지 볼 수 있게 한다. 드롭다운은 그 음식점으로 미리 선택됨.
 *   4. [assignDeviceToRestaurant](PUT /api/devices/{id}/assignment)를 통해
 *      변경을 제출 — 이것이 백엔드에서 SSE 재할당 브로드캐스트를
 *      트리거하는 진입점(데모 시나리오 #3).
 *   5. 인라인 로딩/오류/성공 피드백을 노출해 운영자가 페이지 리로드 없이도
 *      SSE 재할당이 실제 발생했음을 알 수 있게 한다.
 *
 * 합성:
 *   이 컴포넌트는 의도적으로 자기 완결적이고 prop 주도이므로 추가 리팩토링
 *   없이 디바이스 상세 페이지(Sub-AC 3, 형제 AC가 소유)에 임베드 가능.
 *   서버 측 데이터(디바이스 행 + 현재 음식점)는 props로 내려오고, 음식점
 *   목록은 디바이스 행과 독립적으로 변경되므로 클라이언트 측에서 fetch한다.
 */

import { useEffect, useMemo, useState } from "react";
import { ApiError } from "@/lib/api";
import {
  listRestaurants,
  type RestaurantListItem,
} from "@/lib/restaurants";
import {
  assignDeviceToRestaurant,
  type DeviceAssignmentResult,
} from "@/lib/assignments";

/** [RestaurantAssignmentSelector]의 props. */
export interface RestaurantAssignmentSelectorProps {
  /** 할당이 편집되는 디바이스의 UUID. 필수. */
  deviceId: string;
  /** 표시용 사람이 읽을 수 있는 라벨(선택, 예: "Fridge #2"). */
  deviceName?: string;
  /** 현재 음식점 id(있으면) — 드롭다운을 미리 선택하는 데 사용. */
  currentRestaurantId?: string | null;
  /** 현재 음식점 이름(있으면) — "Currently assigned" 라인에 표시. */
  currentRestaurantName?: string | null;
  /**
   * 미리 fetch된 음식점 목록(선택). 제공되면 컴포넌트는 클라이언트 측 fetch를
   * 건너뛴다 — 부모 페이지가 이미 데이터를 가졌을 때 유용(예: 디바이스
   * 상세와 이 UI를 둘 다 렌더링하는 서버 컴포넌트).
   */
  initialRestaurants?: RestaurantListItem[];
  /**
   * 성공한 할당 후 호출됨. 부모는 이를 사용해 풀 페이지 리로드 없이 자체
   * 상태를 새로고침(예: 디바이스 행 재 fetch, 상태 pill 업데이트) 할 수 있다.
   */
  onAssigned?: (result: DeviceAssignmentResult) => void;
}

type LoadState =
  | { kind: "idle" }
  | { kind: "loading" }
  | { kind: "ready"; restaurants: RestaurantListItem[] }
  | { kind: "error"; message: string };

type SubmitState =
  | { kind: "idle" }
  | { kind: "submitting" }
  | { kind: "success"; result: DeviceAssignmentResult }
  | { kind: "error"; message: string };

export function RestaurantAssignmentSelector(
  props: RestaurantAssignmentSelectorProps,
) {
  const {
    deviceId,
    deviceName,
    currentRestaurantId,
    currentRestaurantName,
    initialRestaurants,
    onAssigned,
  } = props;

  // Restaurant list lifecycle. If the parent passed an initial list we skip
  // the network call entirely and start in `ready`.
  const [loadState, setLoadState] = useState<LoadState>(() =>
    initialRestaurants && initialRestaurants.length > 0
      ? { kind: "ready", restaurants: initialRestaurants }
      : { kind: "idle" },
  );

  // The currently selected option in the dropdown. Empty string = the
  // "Choose a restaurant…" placeholder is selected.
  const [selectedId, setSelectedId] = useState<string>(
    currentRestaurantId ?? "",
  );

  // Submit lifecycle (POST/PUT to /api/devices/{id}/assignment).
  const [submitState, setSubmitState] = useState<SubmitState>({ kind: "idle" });

  // Fetch restaurants on mount (unless pre-supplied).
  useEffect(() => {
    if (loadState.kind !== "idle") return;
    let cancelled = false;
    setLoadState({ kind: "loading" });
    listRestaurants()
      .then((restaurants) => {
        if (cancelled) return;
        setLoadState({ kind: "ready", restaurants });
      })
      .catch((err: unknown) => {
        if (cancelled) return;
        setLoadState({ kind: "error", message: describeError(err) });
      });
    return () => {
      cancelled = true;
    };
  }, [loadState.kind]);

  // Keep the dropdown in sync if the parent passes a fresh currentRestaurantId
  // after a successful remap (e.g. via revalidation).
  useEffect(() => {
    if (currentRestaurantId !== undefined && currentRestaurantId !== null) {
      setSelectedId(currentRestaurantId);
    }
  }, [currentRestaurantId]);

  // Allowed dropdown options + a derived "is the selection actually a change?"
  // flag for the submit button.
  const restaurants = loadState.kind === "ready" ? loadState.restaurants : [];
  const hasNoOptions = loadState.kind === "ready" && restaurants.length === 0;

  const isDirty = useMemo(() => {
    if (!selectedId) return false;
    return selectedId !== (currentRestaurantId ?? "");
  }, [selectedId, currentRestaurantId]);

  const submitDisabled =
    !deviceId ||
    !selectedId ||
    !isDirty ||
    submitState.kind === "submitting" ||
    loadState.kind !== "ready";

  async function handleSubmit(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    if (submitDisabled) return;
    setSubmitState({ kind: "submitting" });
    try {
      const result = await assignDeviceToRestaurant(deviceId, selectedId);
      setSubmitState({ kind: "success", result });
      onAssigned?.(result);
    } catch (err) {
      setSubmitState({ kind: "error", message: describeError(err) });
    }
  }

  return (
    <form
      className="assignment-selector"
      aria-label="Assign device to restaurant"
      onSubmit={handleSubmit}
    >
      <header className="assignment-selector__header">
        <strong>Restaurant assignment</strong>
        <span className="muted" style={{ fontSize: 12 }}>
          {deviceName ? `Device: ${deviceName}` : `Device: ${deviceId}`}
        </span>
      </header>

      <div className="assignment-selector__current">
        <span className="muted">Currently assigned:</span>{" "}
        {currentRestaurantId ? (
          <strong>
            {currentRestaurantName || currentRestaurantId}
          </strong>
        ) : (
          <span className="pill pill-warn">Unassigned</span>
        )}
      </div>

      <label
        className="assignment-selector__label"
        htmlFor={`restaurant-select-${deviceId}`}
      >
        Target restaurant
      </label>
      <select
        id={`restaurant-select-${deviceId}`}
        className="assignment-selector__select"
        value={selectedId}
        disabled={loadState.kind !== "ready" || submitState.kind === "submitting"}
        onChange={(e) => setSelectedId(e.target.value)}
        aria-describedby={`restaurant-select-help-${deviceId}`}
      >
        <option value="">
          {loadState.kind === "loading"
            ? "Loading restaurants…"
            : hasNoOptions
              ? "No restaurants available"
              : "Choose a restaurant…"}
        </option>
        {restaurants.map((r) => (
          <option key={r.restaurantId} value={r.restaurantId}>
            {labelFor(r)}
          </option>
        ))}
      </select>
      <div
        id={`restaurant-select-help-${deviceId}`}
        className="muted"
        style={{ fontSize: 12, marginTop: 4 }}
      >
        Picking a different restaurant and clicking Assign immediately remaps
        the device. The device&apos;s player updates within seconds via SSE.
      </div>

      {loadState.kind === "error" && (
        <div className="notice notice-error" role="alert">
          Failed to load restaurants: {loadState.message}
        </div>
      )}

      {submitState.kind === "error" && (
        <div className="notice notice-error" role="alert">
          Assignment failed: {submitState.message}
        </div>
      )}

      {submitState.kind === "success" && (
        <div
          className="notice"
          role="status"
          style={{
            borderColor: "rgba(74, 222, 128, 0.5)",
            background: "rgba(74, 222, 128, 0.08)",
            color: "var(--ok)",
          }}
        >
          Assigned. New active assignment{" "}
          <code>{shortId(submitState.result.assignmentId)}</code> at{" "}
          {submitState.result.assignedAt}.
        </div>
      )}

      <div className="toolbar" style={{ marginTop: 12 }}>
        <button type="submit" className="btn" disabled={submitDisabled}>
          {submitState.kind === "submitting" ? "Assigning…" : "Assign"}
        </button>
        {isDirty && submitState.kind !== "submitting" && (
          <button
            type="button"
            className="btn"
            onClick={() => {
              setSelectedId(currentRestaurantId ?? "");
              setSubmitState({ kind: "idle" });
            }}
          >
            Reset
          </button>
        )}
      </div>
    </form>
  );
}

/* ------------------------------------------------------------------ helpers */

function labelFor(r: RestaurantListItem): string {
  if (r.address && r.restaurantName) {
    return `${r.restaurantName} — ${r.address}`;
  }
  return r.restaurantName || r.restaurantId;
}

function shortId(id: string | undefined | null): string {
  if (!id) return "—";
  if (id.length <= 12) return id;
  return `${id.slice(0, 8)}…${id.slice(-4)}`;
}

function describeError(err: unknown): string {
  if (err instanceof ApiError) {
    return `HTTP ${err.status} (${err.url})`;
  }
  if (err instanceof Error) return err.message;
  return "unknown error";
}

export default RestaurantAssignmentSelector;
