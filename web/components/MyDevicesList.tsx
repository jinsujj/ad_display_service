"use client";

/**
 * 디바이스 목록 + 음식점 매핑 변경 UI의 클라이언트 측 fetch 래퍼.
 *
 * `/devices` 페이지가 서버 컴포넌트로 SSR fetch 하면 토큰이 없어 401 이
 * 떨어지므로, 두 호출(`GET /api/devices`, `GET /api/restaurants`)을 모두
 * 마운트 후 클라이언트에서 수행한다.
 */

import { useEffect, useState } from "react";

import { ApiError } from "@/lib/api";
import { listDevices, type DeviceListItem } from "@/lib/devices";
import { listRestaurants, type RestaurantListItem } from "@/lib/restaurants";
import { DevicesTableClient } from "./DevicesTableClient";

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

  useEffect(() => {
    let cancelled = false;
    Promise.allSettled([listDevices(), listRestaurants()]).then((results) => {
      if (cancelled) return;
      const [devicesResult, restaurantsResult] = results;
      if (devicesResult.status === "rejected") {
        setState({
          kind: "error",
          message: describeError(devicesResult.reason),
        });
        return;
      }
      const devices = devicesResult.value;
      let restaurants: RestaurantListItem[] = [];
      let restaurantsError: string | null = null;
      if (restaurantsResult.status === "fulfilled") {
        restaurants = restaurantsResult.value;
      } else {
        restaurantsError = describeError(restaurantsResult.reason);
      }
      setState({
        kind: "ready",
        devices,
        restaurants,
        restaurantsError,
      });
    });
    return () => {
      cancelled = true;
    };
  }, []);

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
        <DevicesTableClient
          initialDevices={state.devices}
          restaurants={state.restaurants}
        />
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
