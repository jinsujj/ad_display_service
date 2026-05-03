"use client";

/**
 * 디바이스 목록 + 음식점 매핑 변경 UI의 클라이언트 측 fetch 래퍼.
 *
 * `/devices` 페이지가 서버 컴포넌트로 SSR fetch 하면 토큰이 없어 401 이
 * 떨어지므로, 두 호출(`GET /api/devices`, `GET /api/restaurants`)을 모두
 * 마운트 후 클라이언트에서 수행한다.
 */

import { useCallback, useEffect, useState, useTransition } from "react";

import { ApiError } from "@/lib/api";
import { listDevices, type DeviceListItem } from "@/lib/devices";
import { listRestaurants, type RestaurantListItem } from "@/lib/restaurants";
import { DeviceMonitorWall } from "./DeviceMonitorWall";
import { DevicesTableClient } from "./DevicesTableClient";

/** 모니터링 자동 새로고침 주기(밀리초). 1.5초면 디바이스가 다음 광고로 넘어가
 *  자마자 어드민 카드도 따라 잡힌다. /api/devices 는 read-only batch 쿼리라
 *  서버 부담은 미미. 카드 비디오 자체는 onLoadedMetadata 시점에 시킹해서
 *  디바이스 progress 와 시각 동기를 맞춘다. */
const AUTO_REFRESH_MS = 1_500;

type State =
  | { kind: "loading" }
  | {
      kind: "ready";
      devices: DeviceListItem[];
      restaurants: RestaurantListItem[];
      restaurantsError: string | null;
    }
  | { kind: "error"; message: string };

export function MyDevicesList() {
  const [state, setState] = useState<State>({ kind: "loading" });
  const [refreshing, startRefresh] = useTransition();

  const loadAll = useCallback(
    async (mode: "initial" | "refresh"): Promise<void> => {
      const results = await Promise.allSettled([
        listDevices(),
        listRestaurants(),
      ]);
      const [devicesResult, restaurantsResult] = results;
      if (devicesResult.status === "rejected") {
        if (mode === "initial") {
          setState({
            kind: "error",
            message: describeError(devicesResult.reason),
          });
        }
        // refresh 실패 시에는 기존 화면 유지 — 모니터링이 깜빡이지 않게.
        return;
      }
      const devices = devicesResult.value;
      const restaurants =
        restaurantsResult.status === "fulfilled" ? restaurantsResult.value : [];
      const restaurantsError =
        restaurantsResult.status === "rejected"
          ? describeError(restaurantsResult.reason)
          : null;
      setState({ kind: "ready", devices, restaurants, restaurantsError });
    },
    [],
  );

  useEffect(() => {
    let cancelled = false;
    loadAll("initial").catch(() => {
      // loadAll 안에서 이미 setState 처리됨.
    });
    const interval = setInterval(() => {
      if (cancelled) return;
      startRefresh(() => {
        loadAll("refresh");
      });
    }, AUTO_REFRESH_MS);
    return () => {
      cancelled = true;
      clearInterval(interval);
    };
  }, [loadAll]);

  const onManualRefresh = useCallback(() => {
    startRefresh(() => {
      loadAll("refresh");
    });
  }, [loadAll]);

  if (state.kind === "loading") {
    return <div className="muted">디바이스 목록을 불러오는 중…</div>;
  }
  if (state.kind === "error") {
    return (
      <div className="notice notice-error" role="alert">
        백엔드에서 디바이스 목록을 불러오지 못했습니다: {state.message}
      </div>
    );
  }
  return (
    <>
      {state.restaurantsError && (
        <div className="notice notice-error" role="alert">
          디바이스는 불러왔지만 음식점 목록이 사용 불가능합니다:{" "}
          {state.restaurantsError}. 재할당 드롭다운이 비활성화됩니다.
        </div>
      )}
      {state.devices.length === 0 ? (
        <div className="empty-state">
          아직 등록된 디바이스가 없습니다. 광고판 디바이스를 이 백엔드를 향해
          부팅한 뒤 페이지를 새로고침하세요.
        </div>
      ) : (
        <>
          <DeviceMonitorWall
            devices={state.devices}
            onRefresh={onManualRefresh}
            refreshing={refreshing}
          />
          <DevicesTableClient
            initialDevices={state.devices}
            restaurants={state.restaurants}
          />
        </>
      )}
    </>
  );
}

function describeError(err: unknown): string {
  if (err instanceof ApiError) {
    return `HTTP ${err.status}`;
  }
  if (err instanceof Error) return err.message;
  return "알 수 없는 오류";
}

export default MyDevicesList;
