"use client";

/**
 * 디바이스 재할당 모달 (AC 9, Sub-AC 2).
 *
 * 데스크탑은 중앙 정렬 Dialog, 모바일(<md)은 하단 Sheet 로 자동 전환되는
 * ResponsiveDialog 위에 폼을 올린다 — 운영자가 바 뒤에서 손가락으로
 * 연속 재할당할 때 가장 자연스러운 표면.
 *
 * 컴포넌트는 prop 주도이며 mutation 은 부모(DevicesTableClient) 가 소유
 * 한다 — 인라인 에디터와 같은 PATCH 경로를 공유.
 */

import { useEffect, useId, useMemo, useState } from "react";

import type { DeviceListItem } from "@/lib/devices";
import { shortId } from "@/lib/format";
import type { RestaurantListItem } from "@/lib/restaurants";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import { ResponsiveDialog } from "@/components/ui/responsive-dialog";

export interface DeviceRemapModalProps {
  /** 재할당 중인 디바이스. null이면 모달은 아무것도 렌더링하지 않음. */
  device: DeviceListItem | null;
  /** 대상 드롭다운용 음식점 옵션. */
  restaurants: RestaurantListItem[];
  /** 부모가 백엔드 PATCH 를 기다리는 동안 true — 폼 비활성. */
  submitting?: boolean;
  /** 부모의 마지막 제출 시도에서의 인라인 오류(있으면). */
  errorMessage?: string | null;
  /**
   * 운영자가 대상 음식점을 확인할 때 호출. 부모가 실제 mutation
   * (PATCH /api/devices/{deviceId}와 `{ restaurantId }`, AC 9 Sub-AC 3)
   * 과 성공 시 목록 새로고침을 소유한다.
   */
  onSave: (deviceId: string, restaurantId: string) => void;
  /** 모달이 닫혀야 할 때 호출(취소, 백드롭, Escape, 닫기 버튼). */
  onClose: () => void;
}

export function DeviceRemapModal(props: DeviceRemapModalProps) {
  const {
    device,
    restaurants,
    submitting = false,
    errorMessage = null,
    onSave,
    onClose,
  } = props;

  const selectId = useId();

  const currentId = device?.currentRestaurant?.restaurantId ?? "";
  const [selectedId, setSelectedId] = useState<string>(currentId);

  useEffect(() => {
    if (!device) return;
    setSelectedId(device.currentRestaurant?.restaurantId ?? "");
  }, [device]);

  const isDirty = useMemo(() => {
    if (!selectedId) return false;
    return selectedId !== currentId;
  }, [selectedId, currentId]);

  const saveDisabled =
    !selectedId || !isDirty || submitting || restaurants.length === 0;

  if (!device) return null;

  const current = device.currentRestaurant;

  return (
    <ResponsiveDialog
      open={Boolean(device)}
      onOpenChange={(open) => {
        if (!open && !submitting) onClose();
      }}
      title="디바이스 재할당"
      description="저장 즉시 디바이스가 재할당되며 SSE 로 수 초 내에 플레이어에 반영됩니다."
      footer={
        <>
          <Button
            type="button"
            variant="outline"
            onClick={onClose}
            disabled={submitting}
            className="w-full sm:w-auto"
          >
            취소
          </Button>
          <Button
            type="submit"
            form={`remap-form-${device.deviceId}`}
            disabled={saveDisabled}
            className="w-full sm:w-auto"
          >
            {submitting ? "저장 중…" : "저장"}
          </Button>
        </>
      }
    >
      <form
        id={`remap-form-${device.deviceId}`}
        className="space-y-4"
        onSubmit={(e) => {
          e.preventDefault();
          if (!saveDisabled) onSave(device.deviceId, selectedId);
        }}
      >
        <dl className="space-y-2 text-sm">
          <div className="flex flex-wrap items-baseline gap-2">
            <dt className="text-muted-foreground">디바이스</dt>
            <dd className="font-semibold">
              {device.deviceName || "(이름 없음)"}
            </dd>
            <dd
              className="font-mono text-xs text-muted-foreground"
              title={device.deviceId}
            >
              {shortId(device.deviceId)}
            </dd>
          </div>
          <div className="flex flex-wrap items-baseline gap-2">
            <dt className="text-muted-foreground">현재 할당</dt>
            <dd>
              {current ? (
                <strong title={current.restaurantId}>
                  {current.restaurantName || shortId(current.restaurantId)}
                </strong>
              ) : (
                <Badge variant="warn">미할당</Badge>
              )}
            </dd>
          </div>
        </dl>

        <div className="space-y-1.5">
          <Label htmlFor={selectId}>대상 음식점</Label>
          {/* shadcn Select 는 controlled value 가 빈 문자열일 때 placeholder 가
              잘 표현돼 native <select> 를 래핑하지 않고 직접 사용. */}
          <select
            id={selectId}
            value={selectedId}
            disabled={submitting || restaurants.length === 0}
            onChange={(e) => setSelectedId(e.target.value)}
            className="flex h-11 w-full rounded-md border border-input bg-background px-3 py-2 text-sm ring-offset-background focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 disabled:cursor-not-allowed disabled:opacity-50"
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
        </div>

        {errorMessage && (
          <Alert variant="destructive">
            <AlertDescription>재할당 실패: {errorMessage}</AlertDescription>
          </Alert>
        )}
      </form>
    </ResponsiveDialog>
  );
}

function labelFor(r: RestaurantListItem): string {
  if (r.address && r.restaurantName) {
    return `${r.restaurantName} — ${r.address}`;
  }
  return r.restaurantName || r.restaurantId;
}

export default DeviceRemapModal;
