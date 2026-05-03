/**
 * 어드민 UI 의 mutation → list 자동 새로고침을 위한 가벼운 이벤트 버스.
 *
 * Next.js App Router 의 client component 들이 각자 useState 로 데이터를
 * 들고 있어서, 다른 페이지/컴포넌트에서 변경한 결과가 stale 인 채 남는다.
 * router.refresh() 는 server component data 만 다시 가져오므로 이 SPA 모델
 * 에서는 효과가 없다. 그래서 window 의 CustomEvent 로 publish/subscribe.
 *
 * # 사용 패턴
 *
 *   // mutation 직후 발행
 *   await createAd(body);
 *   notifyDataChanged("ad");
 *
 *   // list 컴포넌트
 *   const refetch = useCallback(async () => { ... }, []);
 *   useDataChanged(["ad"], refetch);
 *
 * # 책임 / 비책임
 *  - 책임: 동일 브라우저 탭 안에서 mutation → list 갱신을 맺어준다.
 *  - 비책임: 다른 탭/유저의 변경 — 그건 polling(MyDevicesList 의 3s) 또는
 *    SSE 가 책임. 이 모듈은 *내가* 일으킨 변경을 *내 다른 컴포넌트* 에 알린다.
 */

import { useEffect } from "react";

const EVENT_NAME = "adsignage:data-changed";

/**
 * 변경 종류. list 컴포넌트가 자기 관심사만 react 하도록 분리.
 *  - "ad"          : 광고 CRUD (제목/스케줄/캠페인 변경, 생성, 삭제)
 *  - "video"       : 영상 업로드 / 삭제
 *  - "device"      : 디바이스 CRUD (재할당, 제거)
 *  - "device-queue": 디바이스 광고 큐 add / remove
 */
export type DataKind = "ad" | "video" | "device" | "device-queue";

/**
 * mutation 성공 직후 호출. 같은 브라우저 탭의 모든 listener 가 받는다.
 * SSR / Node 환경에서는 no-op.
 */
export function notifyDataChanged(kind: DataKind): void {
  if (typeof window === "undefined") return;
  try {
    window.dispatchEvent(
      new CustomEvent<DataKind>(EVENT_NAME, { detail: kind }),
    );
  } catch {
    // CustomEvent 미지원 환경(거의 없음) — 무시.
  }
}

/**
 * [kinds] 중 하나라도 발생하면 [onChange] 호출. 추가로 탭이 다시
 * 포커스/visible 이 되면 자동 refetch — 백그라운드 탭에서 돌아오면 fresh.
 *
 * 컴포넌트 unmount 시 listener 자동 제거.
 */
export function useDataChanged(
  kinds: readonly DataKind[],
  onChange: () => void,
): void {
  useEffect(() => {
    const interesting = new Set<DataKind>(kinds);

    const handleData = (e: Event) => {
      const ce = e as CustomEvent<DataKind>;
      if (interesting.has(ce.detail)) onChange();
    };
    const handleFocus = () => {
      // 탭 포커스 / visibility — 백그라운드 시간 동안 변경됐을 수 있으므로 갱신.
      if (document.visibilityState === "visible") onChange();
    };

    window.addEventListener(EVENT_NAME, handleData);
    document.addEventListener("visibilitychange", handleFocus);
    window.addEventListener("focus", handleFocus);

    return () => {
      window.removeEventListener(EVENT_NAME, handleData);
      document.removeEventListener("visibilitychange", handleFocus);
      window.removeEventListener("focus", handleFocus);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [onChange, kinds.join(",")]);
}
