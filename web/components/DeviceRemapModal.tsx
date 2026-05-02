"use client";

/**
 * 디바이스 재할당 모달 (AC 9, Sub-AC 2).
 *
 * 목표:
 *   "Add/extend admin device list page in Next.js (web/) with remap UI controls
 *    (edit action, form/modal) on the device list view."
 *
 * 이 컴포넌트가 하는 일:
 *   - 운영자가 행별 "Edit" / "Reassign" 컨트롤을 클릭하면 디바이스 목록
 *     페이지 위에 중앙 정렬된 모달 다이얼로그를 렌더링한다.
 *   - 재할당 폼(대상 음식점 <select> + Save / Cancel)을 호스팅해 편집
 *     액션이 인라인 테이블 에디터 대신 집중되고 닫을 수 있는 표면에
 *     머무른다 — 운영자가 바 뒤에서 터치 디바이스로 여러 디바이스를 연속
 *     재할당할 때 훨씬 친화적.
 *   - 완전히 prop 주도: 부모(디바이스 목록 페이지의 테이블 클라이언트)가
 *     디바이스+음식점 데이터와 실제 mutation 핸들러를 소유한다. 이 모달은
 *     기존 인라인 에디터가 사용하는 동일한 폼 필드 위의 순수한 표현 셸
 *     이므로, 데모 흐름(운영자가 음식점 선택 → 백엔드 PUT → SSE
 *     MAPPING_CHANGED → 플레이어가 플레이리스트 교체)은 동일하게 유지된다.
 *
 * 접근성:
 *   - role="dialog" + aria-modal="true" + aria-labelledby가 헤딩과 묶임.
 *   - 백드롭 클릭, Escape, Cancel로 닫힘; 선택된 음식점이 현재 매핑된
 *     것과 같으면 Save 비활성(no-op 가드).
 *   - 첫 번째 포커스 가능 요소(<select>)가 열림 시 포커스되어 키보드
 *     사용자가 탭 댄스 없이 즉시 대상을 고를 수 있다.
 *
 * 왜 모달인가(기존 인라인 행 에디터 대비):
 *   인라인 에디터는 파워 유저 + 접근성 폴백을 위해 [DevicesTableClient]에
 *   남지만, 모달은 더 명확한 "이것이 편집 액션이다" 컨트롤을 준다 — Edit
 *   클릭 시 폼이 테이블에서 나와 중앙 정렬되어 AC의 "edit action,
 *   form/modal" 명시 요구사항과 일치한다. 두 경로 모두 동일한
 *   `onSave(restaurantId)` 핸들러로 수렴하므로 underlying 재할당 흐름은
 *   변하지 않는다.
 */

import { useEffect, useId, useMemo, useRef, useState } from "react";

import type { DeviceListItem } from "@/lib/devices";
import type { RestaurantListItem } from "@/lib/restaurants";

export interface DeviceRemapModalProps {
  /** 재할당 중인 디바이스. null이면 모달은 아무것도 렌더링하지 않음. */
  device: DeviceListItem | null;
  /** 대상 드롭다운용 음식점 옵션. */
  restaurants: RestaurantListItem[];
  /** 부모가 백엔드 PUT을 기다리는 동안 true — 폼 비활성. */
  submitting?: boolean;
  /** 부모의 마지막 제출 시도에서의 인라인 오류(있으면). */
  errorMessage?: string | null;
  /**
   * 운영자가 대상 음식점을 확인할 때 호출. 부모가 실제 mutation
   * (PATCH /api/devices/{deviceId}와 `{ restaurantId }`, AC 9 Sub-AC 3)
   * 과 성공 시 목록 새로고침을 소유한다.
   */
  onSave: (deviceId: string, restaurantId: string) => void;
  /** 모달이 닫혀야 할 때 호출(Cancel, 백드롭, Escape, X). */
  onClose: () => void;
}

/**
 * 디바이스를 음식점에 재할당하기 위한 모달 다이얼로그.
 *
 * 컴포넌트는 `device != null`일 때 조건부 렌더링; null이면 호출자가 단일
 * 상태(`editingDevice`)로 열기/닫기를 구동할 수 있도록 null을 반환한다.
 */
export function DeviceRemapModal(props: DeviceRemapModalProps) {
  const {
    device,
    restaurants,
    submitting = false,
    errorMessage = null,
    onSave,
    onClose,
  } = props;

  const titleId = useId();
  const selectId = useId();
  const selectRef = useRef<HTMLSelectElement | null>(null);

  // 로컬 선택 상태 — 디바이스의 현재 음식점으로 초기화되어 드롭다운이
  // 합리적 기본값에서 열린다. 다른 디바이스가 열릴 때마다 리셋.
  const currentId = device?.currentRestaurant?.restaurantId ?? "";
  const [selectedId, setSelectedId] = useState<string>(currentId);

  useEffect(() => {
    if (!device) return;
    setSelectedId(device.currentRestaurant?.restaurantId ?? "");
  }, [device]);

  // 모달이 열릴 때 select에 포커스하여 키보드 사용자가 추가 Tab 없이 즉시
  // 음식점을 고를 수 있게 한다.
  useEffect(() => {
    if (!device) return;
    // 요소가 마운트+가시화되도록 다음 프레임으로 미룬다.
    const id = window.requestAnimationFrame(() => {
      selectRef.current?.focus();
    });
    return () => window.cancelAnimationFrame(id);
  }, [device]);

  // Escape로 닫기 — 매 페이지 방문마다 핸들러를 누수시키지 않도록 모달이
  // 열려있는 동안에만 리스너를 부착.
  useEffect(() => {
    if (!device) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape" && !submitting) {
        onClose();
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [device, onClose, submitting]);

  const isDirty = useMemo(() => {
    if (!selectedId) return false;
    return selectedId !== currentId;
  }, [selectedId, currentId]);

  const saveDisabled =
    !selectedId || !isDirty || submitting || restaurants.length === 0;

  if (!device) return null;

  const current = device.currentRestaurant;

  return (
    <div
      className="modal-backdrop"
      role="presentation"
      onMouseDown={(e) => {
        // 운영자가 다이얼로그 내용에서 드래그해 나가는 게 아니라, 클릭이
        // 백드롭 자체에서 시작될 때만 닫는다.
        if (e.target === e.currentTarget && !submitting) {
          onClose();
        }
      }}
    >
      <div
        className="modal"
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
      >
        <header className="modal__header">
          <h2 id={titleId} className="modal__title">
            Remap device
          </h2>
          <button
            type="button"
            className="modal__close"
            onClick={onClose}
            disabled={submitting}
            aria-label="Close remap dialog"
          >
            ×
          </button>
        </header>

        <form
          className="modal__body"
          onSubmit={(e) => {
            e.preventDefault();
            if (!saveDisabled) onSave(device.deviceId, selectedId);
          }}
        >
          <div className="modal__row">
            <span className="muted">Device:</span>{" "}
            <strong>{device.deviceName || "(unnamed)"}</strong>
            <div className="muted" style={{ fontSize: 12 }}>
              {device.deviceId}
            </div>
          </div>

          <div className="modal__row">
            <span className="muted">Currently assigned:</span>{" "}
            {current ? (
              <strong>
                {current.restaurantName || current.restaurantId}
              </strong>
            ) : (
              <span className="pill pill-warn">Unassigned</span>
            )}
          </div>

          <label className="assignment-selector__label" htmlFor={selectId}>
            Target restaurant
          </label>
          <select
            id={selectId}
            ref={selectRef}
            className="assignment-selector__select"
            value={selectedId}
            disabled={submitting || restaurants.length === 0}
            onChange={(e) => setSelectedId(e.target.value)}
          >
            <option value="">
              {restaurants.length === 0
                ? "No restaurants available"
                : "Choose a restaurant…"}
            </option>
            {restaurants.map((r) => (
              <option key={r.restaurantId} value={r.restaurantId}>
                {labelFor(r)}
              </option>
            ))}
          </select>

          <p className="muted" style={{ fontSize: 12, margin: "4px 0 0" }}>
            Saving immediately remaps the device. The player updates within
            seconds via SSE.
          </p>

          {errorMessage && (
            <div className="notice notice-error" role="alert">
              Remap failed: {errorMessage}
            </div>
          )}

          <div className="modal__footer">
            <button
              type="button"
              className="btn"
              onClick={onClose}
              disabled={submitting}
            >
              Cancel
            </button>
            <button type="submit" className="btn" disabled={saveDisabled}>
              {submitting ? "Saving…" : "Save"}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}

/* ------------------------------------------------------------------ helpers */

function labelFor(r: RestaurantListItem): string {
  if (r.address && r.restaurantName) {
    return `${r.restaurantName} — ${r.address}`;
  }
  return r.restaurantName || r.restaurantId;
}

export default DeviceRemapModal;
