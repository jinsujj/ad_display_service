"use client";

/**
 * 인터랙티브 디바이스 테이블 (AC 40203, Sub-AC 3 + AC 9, Sub-AC 3).
 *
 * 목표:
 *   "Implement mapping change handler that calls backend API to update
 *    device-to-restaurant mapping and refreshes the list."
 *
 *   AC 9, Sub-AC 3 업데이트: "어드민 디바이스 목록 재할당 UI를 API 클라이언트
 *   를 통해 PATCH /api/devices/{deviceId}를 호출하도록 와이어업하고 목록
 *   새로고침과 함께 성공/오류 상태를 처리." 이 sub-AC는 underlying 호출을
 *   레거시 `PUT /api/devices/{id}/assignment` 라우트에서 AC 9, Sub-AC 1이
 *   소유한 일반 `PATCH /api/devices/{deviceId}` 우산 라우트로 교체한다.
 *   사용자에게 보이는 흐름(운영자가 Edit 클릭 → 음식점 선택 → Save → 목록
 *   새로고침)은 동일; 와이어 호출만 이동.
 *
 * 이 컴포넌트가 하는 일:
 *   1. props로 초기 서버 fetch된 [DeviceListItem] 목록과 [RestaurantListItem]
 *      목록을 받음(디바이스 페이지 서버 컴포넌트가 렌더 전에 둘을 fetch).
 *      덕분에 클라이언트 스피너 없이 즉시 테이블을 그릴 수 있다.
 *   2. 디바이스 당 한 행을 인라인 "재할당" 컨트롤과 함께 렌더 — 클릭하면
 *      페이지를 떠나지 않고 행별 에디터(음식점 <select> 드롭다운 + Save /
 *      Cancel)가 펼쳐진다.
 *   3. 이 Sub-AC의 핵심인 **매핑 변경 핸들러** ([handleMappingChange] 아래)를
 *      소유:
 *        a) [patchDevice](lib/devices.ts)를 통해 백엔드 `PATCH /api/devices/{deviceId}`
 *           를 `{ restaurantId }`로 호출;
 *        b) 운영자가 다음 새로고침 전에도 즉시 변경을 보도록 새 음식점을
 *           행에 낙관적으로 병합;
 *        c) `router.refresh()`를 호출해 서버 컴포넌트가 `GET /api/devices`를
 *           재 fetch하고 목록이 권위 있는 백엔드 상태를 반영 — 즉 AC가
 *           명시한 "목록 새로고침".
 *      오류 시 행이 이전 상태로 폴백하고 운영자는 인라인 오류 알림을 본다 —
 *      어떤 것도 조용히 삼키지 않음.
 *
 * 왜 클라이언트 컴포넌트 테이블인가(상세 페이지로의 행별 링크 대비):
 *   AC가 명시적으로 "목록 새로고침"을 말한다 — 즉 변경은 별도 상세 페이지가
 *   아닌 목록 뷰 자체에서 반영되어야 한다. 바 뒤에 있는 운영자는 여러
 *   디바이스를 연속으로 재할당할 것이고, 디바이스마다 상세 페이지를 왕복하면
 *   고통스럽다. 인라인 에디터는 운영자를 목록에 머무르게 하고 `router.refresh()`
 *   와 결합하면 매 변경 후 권위 있는 서버 데이터를 얻는다.
 */

import { useCallback, useState, useTransition } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";

import { ApiError } from "@/lib/api";
import {
  deleteDevice,
  patchDevice,
  type DevicePatchResponse,
  type DeviceListItem,
  type CurrentRestaurant,
} from "@/lib/devices";
import type { RestaurantListItem } from "@/lib/restaurants";
import { DeviceRemapModal } from "./DeviceRemapModal";

export interface DevicesTableClientProps {
  /** 서버 fetch된 디바이스 행. 초기 테이블 상태로 사용. */
  initialDevices: DeviceListItem[];
  /**
   * 인라인 재할당 드롭다운용 음식점 목록. 비어있으면 행은 에디터 없이
   * 렌더링되며 사용 가능한 음식점이 없다는 힌트를 노출한다.
   */
  restaurants: RestaurantListItem[];
}

/** 인라인 에디터/비동기 제출용 행별 UI 상태. */
type RowSubmitState =
  | { kind: "idle" }
  | { kind: "submitting" }
  | { kind: "success"; result: DevicePatchResponse; at: number }
  | { kind: "error"; message: string };

/** 행별 에디터 상태(어떤 행이 현재 편집을 위해 "열려" 있는지 등). */
interface RowEditState {
  /** 이 행의 드롭다운에서 현재 선택된 음식점 id. */
  selectedId: string;
  /** 인라인 에디터가 펼쳐졌는지. */
  open: boolean;
}

const DEFAULT_EDIT: RowEditState = { selectedId: "", open: false };

export function DevicesTableClient(props: DevicesTableClientProps) {
  const { initialDevices, restaurants } = props;
  const router = useRouter();

  // 서버 렌더링 목록의 로컬 미러. 성공한 PUT 직후 낙관적으로 업데이트해서
  // 운영자가 `GET /api/devices` 왕복을 기다리지 않고 변경을 본다. 이 미러는
  // `router.refresh()`가 신선한 서버 데이터를 가져온 후 다음 렌더에서
  // 덮어쓰기 — 그것이 권위 있는 출처.
  const [devices, setDevices] = useState<DeviceListItem[]>(initialDevices);

  // 행별 에디터 + 제출 상태, deviceId로 키. 디바이스 목록 밖에 저장하므로
  // 서버 새로고침 후 재렌더링이 운영자가 아직 읽고 있는 일시적 "Saved"
  // 알림을 지우지 않는다.
  const [editStates, setEditStates] = useState<Record<string, RowEditState>>({});
  const [submitStates, setSubmitStates] = useState<Record<string, RowSubmitState>>({});

  // 모달 기반 재할당(AC 9, Sub-AC 2). 목록 페이지가 이제 행별로 명시적
  // "Edit" 액션을 노출해 기존 인라인 에디터 대신(또는 추가로) 집중된 모달
  // 다이얼로그를 연다. 모달이 현재 편집 중인 디바이스를 추적 — null이면
  // 닫힘.
  const [editingDeviceId, setEditingDeviceId] = useState<string | null>(null);
  const editingDevice = editingDeviceId
    ? devices.find((d) => d.deviceId === editingDeviceId) ?? null
    : null;

  // `useTransition`은 UI를 막지 않고 `router.refresh()`를 호출하게 해준다;
  // 운영자가 성공한 변경 후 목록이 다시 fetch되고 있음을 알도록 테이블
  // 헤더 옆에 `isRefreshing`을 노출한다.
  const [isRefreshing, startRefresh] = useTransition();

  const setEdit = useCallback(
    (deviceId: string, patch: Partial<RowEditState>) => {
      setEditStates((prev) => {
        const cur = prev[deviceId] ?? DEFAULT_EDIT;
        return { ...prev, [deviceId]: { ...cur, ...patch } };
      });
    },
    [],
  );

  const setSubmit = useCallback(
    (deviceId: string, state: RowSubmitState) => {
      setSubmitStates((prev) => ({ ...prev, [deviceId]: state }));
    },
    [],
  );

  /**
   * 매핑 변경 핸들러 — AC가 요구하는 함수. 운영자가 디바이스에 대한
   * 음식점 선택을 확인하면 호출된다.
   *
   * 흐름:
   *   1. 행을 submitting으로 표시(폼 비활성화).
   *   2. [patchDevice]를 통해 PATCH /api/devices/{deviceId}를 `{ restaurantId }`
   *      로. 우산 PATCH 라우트(AC 9, Sub-AC 1)를 사용하면 동일한 와이어
   *      호출이 미래의 디바이스 레벨 필드(screenName, groupName, …)를 추가
   *      컨트롤러 홉 없이 흡수한다.
   *   3. 성공:
   *      - 다음 서버 fetch가 도착하기 전에 테이블이 변경을 반영하도록
   *        행의 `currentRestaurant`를 낙관적으로 패치;
   *      - 인라인 에디터와 모달(열려 있다면)을 닫아 운영자가 새 매핑이
   *        보이는 목록으로 폴백;
   *      - 서버 컴포넌트가 `listDevices()`를 재실행하고 목록이 권위 있는
   *        백엔드 데이터로 교체되도록 `router.refresh()`를 시작("목록
   *        새로고침").
   *   4. 오류: 운영자가 컨텍스트 손실 없이 수정+재시도할 수 있도록 에디터/
   *      모달을 열어 두고 인라인 오류를 표시.
   */
  const handleMappingChange = useCallback(
    async (deviceId: string, restaurantId: string) => {
      if (!deviceId || !restaurantId) return;

      const targetRestaurant = restaurants.find(
        (r) => r.restaurantId === restaurantId,
      );

      setSubmit(deviceId, { kind: "submitting" });

      try {
        const result = await patchDevice(deviceId, { restaurantId });

        // 낙관적 로컬 업데이트: 이 디바이스의 currentRestaurant를 방금
        // 할당된 것으로 교체하여 `router.refresh()` 왕복 전에 행이 새 매핑을
        // 반영하게 한다. 라벨용으로 드롭다운의 음식점 메타데이터(name/address)
        // 사용 — 백엔드 결과는 ids/timestamps만 담는다. PATCH 응답이 결정된
        // restaurantId를 예기치 않게 누락하면(예: 미래 sub-AC의 unassign
        // 스타일 응답) 운영자가 방금 클릭한 것을 행이 여전히 반영하도록
        // 운영자 선택 id로 폴백한다.
        setDevices((prev) =>
          prev.map((d) =>
            d.deviceId === deviceId
              ? {
                  ...d,
                  currentRestaurant: buildOptimisticAssignment(
                    { ...result, restaurantId: result.restaurantId ?? restaurantId },
                    targetRestaurant,
                  ),
                }
              : d,
          ),
        );

        setSubmit(deviceId, { kind: "success", result, at: Date.now() });
        setEdit(deviceId, { open: false });

        // 변경이 모달 경로에서 왔다면 모달도 닫아 운영자가 새 매핑이
        // 보이는 목록으로 폴백.
        setEditingDeviceId((cur) => (cur === deviceId ? null : cur));

        // 목록이 권위 있는 데이터를 보이도록 서버 컴포넌트 새로고침.
        startRefresh(() => {
          router.refresh();
        });
      } catch (err) {
        setSubmit(deviceId, {
          kind: "error",
          message: describeError(err),
        });
      }
    },
    [restaurants, router, setEdit, setSubmit, startRefresh],
  );

  /**
   * 모달 진입점 — `deviceId`에 대한 전용 재할당 다이얼로그를 연다.
   * 모달이 깨끗한 상태에서 열리도록 stale 행별 오류를 지운다.
   */
  const openRemapModal = useCallback(
    (deviceId: string) => {
      setSubmit(deviceId, { kind: "idle" });
      setEditingDeviceId(deviceId);
    },
    [setSubmit],
  );

  const closeRemapModal = useCallback(() => {
    setEditingDeviceId(null);
  }, []);

  /**
   * 디바이스 제거 — confirm 후 DELETE /api/devices/{id}.
   * 행은 즉시 로컬 미러에서 빠지고, router.refresh() 로 서버 권위 재조회.
   * 디바이스 앱이 다시 켜지면 자동 재등록되므로 안전한 정리 동작.
   */
  const handleDelete = useCallback(
    async (device: DeviceListItem) => {
      const label = device.deviceName || device.deviceId;
      const ok = window.confirm(
        `디바이스 "${label}" 를 어드민에서 제거할까요?\n\n` +
          `· 매핑 이력도 함께 삭제됩니다.\n` +
          `· 디바이스 앱이 다음에 켜지면 자기 ID 로 자동 재등록되어 다시 표시됩니다.`,
      );
      if (!ok) return;

      setSubmit(device.deviceId, { kind: "submitting" });
      try {
        await deleteDevice(device.deviceId);
        // 로컬 즉시 제거 + 서버 새로고침
        setDevices((prev) => prev.filter((d) => d.deviceId !== device.deviceId));
        setEditingDeviceId((cur) => (cur === device.deviceId ? null : cur));
        startRefresh(() => router.refresh());
      } catch (err) {
        setSubmit(device.deviceId, {
          kind: "error",
          message: describeError(err),
        });
      }
    },
    [router, setSubmit, startRefresh],
  );

  return (
    <div>
      <div className="toolbar" style={{ marginBottom: 8 }}>
        {isRefreshing ? (
          <span className="muted" role="status">
            목록 새로고침 중…
          </span>
        ) : (
          <span className="muted">
            디바이스 {devices.length}개
          </span>
        )}
      </div>

      {/* 데스크톱 — 6컬럼 테이블. 좁은 화면에선 CSS 가 숨김. */}
      <div className="devices-table-wrap">
        <table className="data-table" aria-label="디바이스">
          <colgroup>
            <col style={{ width: 180 }} />
            <col style={{ width: 200 }} />
            <col style={{ width: 130 }} />
            <col />
            <col style={{ width: 130 }} />
            <col style={{ width: 220 }} />
          </colgroup>
          <thead>
            <tr>
              <th scope="col">디바이스</th>
              <th scope="col">디바이스 ID</th>
              <th scope="col">등록일</th>
              <th scope="col">현재 음식점</th>
              <th scope="col">매핑 시각</th>
              <th scope="col">재할당</th>
            </tr>
          </thead>
          <tbody>
            {devices.map((device) => {
              const edit = editStates[device.deviceId] ?? DEFAULT_EDIT;
              const submit = submitStates[device.deviceId] ?? { kind: "idle" };
              return (
                <DeviceRow
                  key={device.deviceId || device.deviceName}
                  device={device}
                  restaurants={restaurants}
                  edit={edit}
                  submit={submit}
                  onToggleEdit={(open) =>
                    setEdit(device.deviceId, {
                      open,
                      // 열 때, 드롭다운이 합리적 기본 상태에 있도록 현재
                      // 음식점을 미리 선택.
                      selectedId: open
                        ? device.currentRestaurant?.restaurantId ?? ""
                        : "",
                    })
                  }
                  onSelectRestaurant={(id) =>
                    setEdit(device.deviceId, { selectedId: id })
                  }
                  onSave={() =>
                    handleMappingChange(device.deviceId, edit.selectedId)
                  }
                  onClearStatus={() => setSubmit(device.deviceId, { kind: "idle" })}
                  onOpenModal={() => openRemapModal(device.deviceId)}
                  onDelete={() => handleDelete(device)}
                />
              );
            })}
          </tbody>
        </table>
      </div>

      {/* 모바일 — 같은 데이터를 세로 카드 리스트로. 테이블의 가로 스크롤
          (≈860px 폭) 을 대체. ≤720px 화면에서만 보인다. 인라인 에디터는
          좁은 화면에서 어색하므로 카드 액션은 모달(`Edit`) 과 제거만 노출. */}
      <ul className="devices-cards" aria-label="디바이스 (모바일 보기)">
        {devices.map((device) => {
          const submit = submitStates[device.deviceId] ?? { kind: "idle" };
          return (
            <DeviceCard
              key={device.deviceId || device.deviceName}
              device={device}
              restaurants={restaurants}
              submit={submit}
              onClearStatus={() => setSubmit(device.deviceId, { kind: "idle" })}
              onOpenModal={() => openRemapModal(device.deviceId)}
              onDelete={() => handleDelete(device)}
            />
          );
        })}
      </ul>

      {/* 모달 기반 재할당 표면(AC 9, Sub-AC 2). `editingDevice`가 non-null일
          때만 렌더링. 모달은 onSave를 통해 `handleMappingChange`를 호출하므로
          인라인 에디터와 동일한 PUT + 새로고침 경로를 공유한다. */}
      <DeviceRemapModal
        device={editingDevice}
        restaurants={restaurants}
        submitting={
          editingDeviceId
            ? (submitStates[editingDeviceId]?.kind ?? "idle") === "submitting"
            : false
        }
        errorMessage={
          editingDeviceId &&
          submitStates[editingDeviceId]?.kind === "error"
            ? (submitStates[editingDeviceId] as { kind: "error"; message: string })
                .message
            : null
        }
        onSave={(deviceId, restaurantId) =>
          handleMappingChange(deviceId, restaurantId)
        }
        onClose={closeRemapModal}
      />
    </div>
  );
}

/* --------------------------------------------------------------- row */

interface DeviceRowProps {
  device: DeviceListItem;
  restaurants: RestaurantListItem[];
  edit: RowEditState;
  submit: RowSubmitState;
  onToggleEdit: (open: boolean) => void;
  onSelectRestaurant: (id: string) => void;
  onSave: () => void;
  onClearStatus: () => void;
  /** 이 행에 대한 모달 기반 재할당 다이얼로그를 연다(AC 9, Sub-AC 2). */
  onOpenModal: () => void;
  /** 디바이스를 어드민에서 제거(앱 재시작 시 자동 재등록). */
  onDelete: () => void;
}

function DeviceRow(props: DeviceRowProps) {
  const {
    device,
    restaurants,
    edit,
    submit,
    onToggleEdit,
    onSelectRestaurant,
    onSave,
    onClearStatus,
    onOpenModal,
    onDelete,
  } = props;

  const current = device.currentRestaurant;
  const submitting = submit.kind === "submitting";

  // 운영자가 현재 할당된 것과 *다른* 음식점을 골랐을 때만 저장 가능.
  // 같은 id로 저장하는 것은 백엔드로의 무동작 왕복일 뿐.
  const isDirty = !!edit.selectedId && edit.selectedId !== (current?.restaurantId ?? "");
  const saveDisabled =
    !edit.selectedId || !isDirty || submitting || restaurants.length === 0;

  return (
    <>
      <tr>
        <td>
          <strong>{device.deviceName || "(이름 없음)"}</strong>
        </td>
        <td className="id" title={device.deviceId}>
          <Link href={`/devices/${encodeURIComponent(device.deviceId)}`}>
            {shortId(device.deviceId)}
          </Link>
        </td>
        <td>
          <span className="muted">{formatDate(device.registeredAt)}</span>
        </td>
        <td>
          {current ? (
            <div>
              <div>
                <strong>{current.restaurantName || "(음식점 이름 없음)"}</strong>
              </div>
              {current.address && (
                <div className="muted" style={{ fontSize: 12 }}>
                  {current.address}
                </div>
              )}
            </div>
          ) : (
            <span className="pill pill-warn">미할당</span>
          )}
        </td>
        <td>
          {current ? (
            <span className="muted">{formatDate(current.assignedAt)}</span>
          ) : (
            <span className="muted">—</span>
          )}
        </td>
        <td>
          {!edit.open && (
            <div className="toolbar" style={{ flexWrap: "wrap" }}>
              {/* 주요 편집 액션 — 전용 재할당 모달 열기(AC 9, Sub-AC 2).
                  연필 글리프를 사용해 한눈에 "edit" 컨트롤로 읽힌다. */}
              <button
                type="button"
                className="btn"
                onClick={onOpenModal}
                disabled={submitting || restaurants.length === 0}
                title={
                  restaurants.length === 0
                    ? "재할당 가능한 음식점이 없습니다"
                    : "이 디바이스에 대한 재할당 다이얼로그 열기"
                }
                aria-label={`Edit mapping for ${device.deviceName || device.deviceId}`}
              >
                <span aria-hidden="true">✎</span> Edit
              </button>
              {/* 보조 인라인 에디터 — 다이얼로그를 열지 않고 테이블에
                  머무르고 싶은 파워 유저용. */}
              <button
                type="button"
                className="btn"
                onClick={() => onToggleEdit(true)}
                disabled={submitting || restaurants.length === 0}
                title="다이얼로그 없이 행에서 바로 재할당"
              >
                {current ? "재할당" : "할당"}
              </button>
              {/* 디바이스 제거 — 분실/교체/테스트 정리용. 디바이스 앱이
                  재시작되면 자기 ID 로 자동 재등록되므로 안전. */}
              <button
                type="button"
                className="btn"
                onClick={onDelete}
                disabled={submitting}
                title="이 디바이스 제거 (앱 재시작 시 자동 재등록)"
                aria-label={`Delete device ${device.deviceName || device.deviceId}`}
                style={{ color: "var(--err)", borderColor: "rgba(239,68,68,0.35)" }}
              >
                ✕ 제거
              </button>
            </div>
          )}
          {edit.open && (
            <span className="muted" style={{ fontSize: 12 }}>
              편집 중…
            </span>
          )}
        </td>
      </tr>

      {edit.open && (
        <tr className="device-row-editor">
          <td colSpan={6}>
            <form
              className="assignment-selector"
              style={{ maxWidth: "100%" }}
              onSubmit={(e) => {
                e.preventDefault();
                if (!saveDisabled) onSave();
              }}
              aria-label={`Reassign ${device.deviceName || device.deviceId}`}
            >
              <div className="assignment-selector__current">
                <span className="muted">현재 할당:</span>{" "}
                {current ? (
                  <strong>
                    {current.restaurantName || current.restaurantId}
                  </strong>
                ) : (
                  <span className="pill pill-warn">미할당</span>
                )}
              </div>

              <label
                className="assignment-selector__label"
                htmlFor={`reassign-select-${device.deviceId}`}
              >
                대상 음식점
              </label>
              <select
                id={`reassign-select-${device.deviceId}`}
                className="assignment-selector__select"
                value={edit.selectedId}
                disabled={submitting || restaurants.length === 0}
                onChange={(e) => onSelectRestaurant(e.target.value)}
              >
                <option value="">
                  {restaurants.length === 0
                    ? "사용 가능한 음식점 없음"
                    : "음식점 선택…"}
                </option>
                {restaurants.map((r) => (
                  <option key={r.restaurantId} value={r.restaurantId}>
                    {labelFor(r)}
                  </option>
                ))}
              </select>

              {submit.kind === "error" && (
                <div className="notice notice-error" role="alert">
                  할당 실패: {submit.message}
                </div>
              )}

              <div className="toolbar" style={{ marginTop: 8 }}>
                <button type="submit" className="btn" disabled={saveDisabled}>
                  {submitting ? "저장 중…" : "저장"}
                </button>
                <button
                  type="button"
                  className="btn"
                  onClick={() => {
                    onClearStatus();
                    onToggleEdit(false);
                  }}
                  disabled={submitting}
                >
                  Cancel
                </button>
              </div>
            </form>
          </td>
        </tr>
      )}

      {!edit.open && submit.kind === "success" && (
        <tr className="device-row-status">
          <td colSpan={6}>
            <div
              className="notice"
              role="status"
              style={{
                borderColor: "rgba(74, 222, 128, 0.5)",
                background: "rgba(74, 222, 128, 0.08)",
                color: "var(--ok)",
                marginBottom: 0,
              }}
            >
              매핑이 업데이트되었습니다.
              {submit.result.assignmentId ? (
                <>
                  {" "}활성 할당{" "}
                  <code>{shortId(submit.result.assignmentId)}</code>
                  {submit.result.assignedAt
                    ? ` at ${submit.result.assignedAt}`
                    : ""}
                  .
                </>
              ) : null}{" "}
              <button
                type="button"
                className="btn"
                style={{ marginLeft: 8 }}
                onClick={onClearStatus}
              >
                Dismiss
              </button>
            </div>
          </td>
        </tr>
      )}
    </>
  );
}

/* ----------------------------------------------------------- mobile card */

interface DeviceCardProps {
  device: DeviceListItem;
  restaurants: RestaurantListItem[];
  submit: RowSubmitState;
  onClearStatus: () => void;
  onOpenModal: () => void;
  onDelete: () => void;
}

/**
 * 모바일/좁은 화면용 디바이스 카드. 데스크톱 테이블 행과 동일한 데이터를
 * 세로 스택으로 표시한다. 인라인 에디터는 모바일에선 답답해서 의도적으로
 * 생략 — 재할당은 모달(`Edit`) 로 일원화.
 */
function DeviceCard({
  device,
  restaurants,
  submit,
  onClearStatus,
  onOpenModal,
  onDelete,
}: DeviceCardProps) {
  const current = device.currentRestaurant;
  const submitting = submit.kind === "submitting";
  const noRestaurants = restaurants.length === 0;

  return (
    <li className="device-card">
      <div className="device-card__head">
        <div className="device-card__title">
          <strong>{device.deviceName || "(이름 없음)"}</strong>
          <Link
            href={`/devices/${encodeURIComponent(device.deviceId)}`}
            className="device-card__id"
            title={device.deviceId}
          >
            {shortId(device.deviceId)}
          </Link>
        </div>
        <div className="device-card__meta muted">
          등록 {formatDate(device.registeredAt)}
        </div>
      </div>

      <div className="device-card__assignment">
        {current ? (
          <>
            <div className="device-card__restaurant">
              <span aria-hidden="true">📍 </span>
              <strong>{current.restaurantName || "(음식점 이름 없음)"}</strong>
            </div>
            {current.address && (
              <div className="muted device-card__address">{current.address}</div>
            )}
            <div className="muted device-card__mapped">
              매핑 {formatDate(current.assignedAt)}
            </div>
          </>
        ) : (
          <span className="pill pill-warn">미할당</span>
        )}
      </div>

      {submit.kind === "error" && (
        <div className="notice notice-error" role="alert">
          {submit.message}
        </div>
      )}

      {submit.kind === "success" && (
        <div
          className="notice"
          role="status"
          style={{
            borderColor: "rgba(74, 222, 128, 0.5)",
            background: "rgba(74, 222, 128, 0.08)",
            color: "var(--ok)",
            marginBottom: 0,
          }}
        >
          매핑이 업데이트되었습니다.
          <button
            type="button"
            className="btn"
            style={{ marginLeft: 8 }}
            onClick={onClearStatus}
          >
            Dismiss
          </button>
        </div>
      )}

      <div className="device-card__actions toolbar">
        <button
          type="button"
          className="btn"
          onClick={onOpenModal}
          disabled={submitting || noRestaurants}
          title={
            noRestaurants
              ? "재할당 가능한 음식점이 없습니다"
              : "이 디바이스에 대한 재할당 다이얼로그 열기"
          }
          aria-label={`Edit mapping for ${device.deviceName || device.deviceId}`}
        >
          <span aria-hidden="true">✎</span> {current ? "재할당" : "할당"}
        </button>
        <button
          type="button"
          className="btn device-card__delete"
          onClick={onDelete}
          disabled={submitting}
          title="이 디바이스 제거 (앱 재시작 시 자동 재등록)"
          aria-label={`Delete device ${device.deviceName || device.deviceId}`}
        >
          <span aria-hidden="true">✕</span> 제거
        </button>
      </div>
    </li>
  );
}

/* ------------------------------------------------------------ helpers */

/**
 * 성공한 PATCH 후, 서버 측 새로고침 왕복이 도착하기 전에 행에 끼워 넣을
 * 낙관적 [CurrentRestaurant] 항목을 만든다. 드롭다운 행 메타데이터가
 * 누락됐거나 백엔드가 `assignedAt`(오래된 응답 형태)을 생략한 경우 우아하게
 * 폴백한다.
 */
function buildOptimisticAssignment(
  result: { restaurantId: string | null; assignedAt: string | null },
  meta: RestaurantListItem | undefined,
): CurrentRestaurant {
  return {
    restaurantId: result.restaurantId ?? meta?.restaurantId ?? "",
    restaurantName: meta?.restaurantName ?? "",
    address: meta?.address ?? null,
    assignedAt: result.assignedAt ?? new Date().toISOString(),
  };
}

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

function formatDate(iso: string | undefined | null): string {
  if (!iso) return "—";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  return d.toISOString().replace("T", " ").slice(0, 16) + " UTC";
}

function describeError(err: unknown): string {
  if (err instanceof ApiError) return `HTTP ${err.status} (${err.url})`;
  if (err instanceof Error) return err.message;
  return "unknown error";
}

export default DevicesTableClient;
