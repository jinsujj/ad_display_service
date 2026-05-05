"use client";

/**
 * 인터랙티브 디바이스 테이블 (AC 40203, Sub-AC 3 + AC 9, Sub-AC 3).
 *
 * 목표: "Implement mapping change handler that calls backend API to update
 * device-to-restaurant mapping and refreshes the list."
 *
 * 데스크탑(>=md) 은 6컬럼 shadcn Table, 모바일(<md) 은 카드 리스트로 자동
 * 전환된다 (`hidden md:block` / `md:hidden` 듀얼 렌더). 재할당은 양 표면
 * 모두 ResponsiveDialog 기반 DeviceRemapModal 을 통해 동일한
 * `handleMappingChange` 경로로 수렴.
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
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";

export interface DevicesTableClientProps {
  initialDevices: DeviceListItem[];
  restaurants: RestaurantListItem[];
}

type RowSubmitState =
  | { kind: "idle" }
  | { kind: "submitting" }
  | { kind: "success"; result: DevicePatchResponse; at: number }
  | { kind: "error"; message: string };

export function DevicesTableClient(props: DevicesTableClientProps) {
  const { initialDevices, restaurants } = props;
  const router = useRouter();

  const [devices, setDevices] = useState<DeviceListItem[]>(initialDevices);
  const [submitStates, setSubmitStates] = useState<
    Record<string, RowSubmitState>
  >({});

  const [editingDeviceId, setEditingDeviceId] = useState<string | null>(null);
  const editingDevice = editingDeviceId
    ? devices.find((d) => d.deviceId === editingDeviceId) ?? null
    : null;

  const [isRefreshing, startRefresh] = useTransition();

  const setSubmit = useCallback(
    (deviceId: string, state: RowSubmitState) => {
      setSubmitStates((prev) => ({ ...prev, [deviceId]: state }));
    },
    [],
  );

  const handleMappingChange = useCallback(
    async (deviceId: string, restaurantId: string) => {
      if (!deviceId || !restaurantId) return;

      const targetRestaurant = restaurants.find(
        (r) => r.restaurantId === restaurantId,
      );

      setSubmit(deviceId, { kind: "submitting" });

      try {
        const result = await patchDevice(deviceId, { restaurantId });

        // 낙관적 업데이트: router.refresh() 왕복 전에 운영자가 즉시 변경을
        // 본다. 응답이 restaurantId 누락 시 클릭한 id 로 폴백.
        setDevices((prev) =>
          prev.map((d) =>
            d.deviceId === deviceId
              ? {
                  ...d,
                  currentRestaurant: buildOptimisticAssignment(
                    {
                      ...result,
                      restaurantId: result.restaurantId ?? restaurantId,
                    },
                    targetRestaurant,
                  ),
                }
              : d,
          ),
        );

        setSubmit(deviceId, { kind: "success", result, at: Date.now() });
        setEditingDeviceId((cur) => (cur === deviceId ? null : cur));

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
    [restaurants, router, setSubmit, startRefresh],
  );

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
        setDevices((prev) =>
          prev.filter((d) => d.deviceId !== device.deviceId),
        );
        setEditingDeviceId((cur) =>
          cur === device.deviceId ? null : cur,
        );
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
      <div
        className="mb-2 text-xs text-muted-foreground"
        role="status"
        aria-live="polite"
      >
        {isRefreshing ? "목록 새로고침 중…" : `디바이스 ${devices.length}개`}
      </div>

      {/* 데스크탑 — 디바이스 ID 컬럼은 광고주에게 의미 없으므로 숨김.
          디바이스명 자체가 상세 페이지로 가는 링크 역할. */}
      <div className="hidden md:block w-full overflow-x-auto rounded-lg border border-border bg-card">
        <Table aria-label="디바이스">
          <TableHeader>
            <TableRow>
              <TableHead className="min-w-[200px]">디바이스</TableHead>
              <TableHead className="w-[140px]">등록일</TableHead>
              <TableHead className="min-w-[200px]">현재 음식점</TableHead>
              <TableHead className="w-[140px]">매핑 시각</TableHead>
              <TableHead className="w-[200px] text-right">액션</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {devices.map((device) => {
              const submit = submitStates[device.deviceId] ?? {
                kind: "idle" as const,
              };
              return (
                <DeviceRow
                  key={device.deviceId || device.deviceName}
                  device={device}
                  submit={submit}
                  noRestaurants={restaurants.length === 0}
                  onClearStatus={() =>
                    setSubmit(device.deviceId, { kind: "idle" })
                  }
                  onOpenModal={() => openRemapModal(device.deviceId)}
                  onDelete={() => handleDelete(device)}
                />
              );
            })}
          </TableBody>
        </Table>
      </div>

      {/* 모바일 — 같은 데이터를 세로 카드 리스트로. 가로 스크롤 회피. */}
      <ul
        className="md:hidden flex flex-col gap-2.5"
        aria-label="디바이스 (모바일 보기)"
      >
        {devices.map((device) => {
          const submit = submitStates[device.deviceId] ?? {
            kind: "idle" as const,
          };
          return (
            <DeviceCard
              key={device.deviceId || device.deviceName}
              device={device}
              submit={submit}
              noRestaurants={restaurants.length === 0}
              onClearStatus={() =>
                setSubmit(device.deviceId, { kind: "idle" })
              }
              onOpenModal={() => openRemapModal(device.deviceId)}
              onDelete={() => handleDelete(device)}
            />
          );
        })}
      </ul>

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
            ? (submitStates[editingDeviceId] as {
                kind: "error";
                message: string;
              }).message
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
  submit: RowSubmitState;
  noRestaurants: boolean;
  onClearStatus: () => void;
  onOpenModal: () => void;
  onDelete: () => void;
}

function DeviceRow({
  device,
  submit,
  noRestaurants,
  onClearStatus,
  onOpenModal,
  onDelete,
}: DeviceRowProps) {
  const current = device.currentRestaurant;
  const submitting = submit.kind === "submitting";

  return (
    <>
      <TableRow>
        <TableCell>
          <Link
            href={`/devices/${encodeURIComponent(device.deviceId)}`}
            className="font-semibold text-foreground hover:text-accent hover:underline underline-offset-4"
          >
            {device.deviceName || "(이름 없음)"}
          </Link>
        </TableCell>
        <TableCell className="text-xs text-muted-foreground">
          {formatDate(device.registeredAt)}
        </TableCell>
        <TableCell>
          {current ? (
            <div>
              <div className="font-semibold">
                {current.restaurantName || "(음식점 이름 없음)"}
              </div>
              {current.address && (
                <div className="text-xs text-muted-foreground">
                  {current.address}
                </div>
              )}
            </div>
          ) : (
            <Badge variant="warn">미할당</Badge>
          )}
        </TableCell>
        <TableCell className="text-xs text-muted-foreground">
          {current ? formatDate(current.assignedAt) : "—"}
        </TableCell>
        <TableCell>
          <div className="flex flex-wrap items-center justify-end gap-1.5">
            <Button
              type="button"
              variant="outline"
              size="sm"
              onClick={onOpenModal}
              disabled={submitting || noRestaurants}
              title={
                noRestaurants
                  ? "재할당 가능한 음식점이 없습니다"
                  : "이 디바이스에 대한 재할당 다이얼로그 열기"
              }
              aria-label={`Edit mapping for ${device.deviceName || device.deviceId}`}
            >
              <span aria-hidden="true">✎</span>{" "}
              {current ? "재할당" : "할당"}
            </Button>
            <Button
              type="button"
              variant="outline"
              size="sm"
              onClick={onDelete}
              disabled={submitting}
              title="이 디바이스 제거 (앱 재시작 시 자동 재등록)"
              aria-label={`Delete device ${device.deviceName || device.deviceId}`}
              className="border-destructive/35 text-destructive hover:bg-destructive/10"
            >
              ✕ 제거
            </Button>
          </div>
        </TableCell>
      </TableRow>
      {submit.kind === "success" && (
        <TableRow>
          <TableCell colSpan={5} className="p-0">
            <div className="m-2 flex items-center justify-between gap-2 rounded-md border border-emerald-500/40 bg-emerald-500/10 px-3 py-2 text-sm text-emerald-300">
              <span>매핑이 업데이트되었습니다.</span>
              <Button
                type="button"
                variant="ghost"
                size="sm"
                onClick={onClearStatus}
              >
                Dismiss
              </Button>
            </div>
          </TableCell>
        </TableRow>
      )}
      {submit.kind === "error" && (
        <TableRow>
          <TableCell colSpan={5} className="p-0">
            <Alert variant="destructive" className="m-2">
              <AlertDescription>{submit.message}</AlertDescription>
            </Alert>
          </TableCell>
        </TableRow>
      )}
    </>
  );
}

/* ----------------------------------------------------------- mobile card */

interface DeviceCardProps {
  device: DeviceListItem;
  submit: RowSubmitState;
  noRestaurants: boolean;
  onClearStatus: () => void;
  onOpenModal: () => void;
  onDelete: () => void;
}

function DeviceCard({
  device,
  submit,
  noRestaurants,
  onClearStatus,
  onOpenModal,
  onDelete,
}: DeviceCardProps) {
  const current = device.currentRestaurant;
  const submitting = submit.kind === "submitting";

  return (
    <li className="rounded-lg border border-border bg-card p-3 text-card-foreground">
      <Link
        href={`/devices/${encodeURIComponent(device.deviceId)}`}
        className="block"
      >
        <strong className="block truncate text-base text-foreground hover:text-accent">
          {device.deviceName || "(이름 없음)"}
        </strong>
        <div className="mt-0.5 text-xs text-muted-foreground">
          등록 {formatDate(device.registeredAt)}
        </div>
      </Link>

      <div className="mt-3 space-y-1 text-sm">
        {current ? (
          <>
            <div className="font-semibold">
              <span aria-hidden="true">📍 </span>
              {current.restaurantName || "(음식점 이름 없음)"}
            </div>
            {current.address && (
              <div className="text-xs text-muted-foreground">
                {current.address}
              </div>
            )}
            <div className="text-xs text-muted-foreground">
              매핑 {formatDate(current.assignedAt)}
            </div>
          </>
        ) : (
          <Badge variant="warn">미할당</Badge>
        )}
      </div>

      {submit.kind === "error" && (
        <Alert variant="destructive" className="mt-3">
          <AlertDescription>{submit.message}</AlertDescription>
        </Alert>
      )}

      {submit.kind === "success" && (
        <div className="mt-3 flex items-center justify-between gap-2 rounded-md border border-emerald-500/40 bg-emerald-500/10 px-3 py-2 text-sm text-emerald-300">
          <span>매핑이 업데이트되었습니다.</span>
          <Button
            type="button"
            variant="ghost"
            size="sm"
            onClick={onClearStatus}
          >
            Dismiss
          </Button>
        </div>
      )}

      <div className="mt-3 flex gap-2">
        <Button
          type="button"
          onClick={onOpenModal}
          disabled={submitting || noRestaurants}
          title={
            noRestaurants
              ? "재할당 가능한 음식점이 없습니다"
              : "이 디바이스에 대한 재할당 다이얼로그 열기"
          }
          aria-label={`Edit mapping for ${device.deviceName || device.deviceId}`}
          className="flex-1"
        >
          <span aria-hidden="true">✎</span> {current ? "재할당" : "할당"}
        </Button>
        <Button
          type="button"
          variant="outline"
          onClick={onDelete}
          disabled={submitting}
          title="이 디바이스 제거 (앱 재시작 시 자동 재등록)"
          aria-label={`Delete device ${device.deviceName || device.deviceId}`}
          className="border-destructive/35 text-destructive hover:bg-destructive/10"
        >
          ✕ 제거
        </Button>
      </div>
    </li>
  );
}

/* ------------------------------------------------------------ helpers */

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
