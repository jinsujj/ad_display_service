/**
 * 광고별 일일 재생 카운터(AC 3, Sub-AC 2).
 *
 * 로컬 자정에 롤오버되는 플레이어 측의 일일 재생 횟수 추적과 상한 강제를
 * 담당:
 *
 *   "플레이어에 daily_play_count 추적 및 상한 강제 구현(광고별 일일 카운터를
 *    날짜 롤오버와 함께 유지)하여 한도에 도달하면 재생 중지."
 *
 * ## 별도 모듈로 둔 이유
 *
 * `PlayerClient.tsx`는 이미 SSE / 플레이리스트 상태 / 라운드 로빈 / 스케줄
 * 윈도우 필터링을 연결한다. 카운터 영속화를 그 I/O와 섞으면 React 이펙트와
 * 스토리지 로직 모두 감사하기 어려워진다. 카운터 알고리즘은 순수 산술과
 * 작은 localStorage 레이어로 구성된다 — 부수효과 없는 모듈로 추출하면:
 *
 *   - 상한 강제 계약을 한 곳으로 모은다,
 *   - 헬퍼들을 DOM 없이 `node --test`에서 단위 테스트할 수 있다
 *     (`roundRobin.test.mjs`와 `playlist.schedule.test.mjs`에서 사용된 동일한
 *     vendored-impl 패턴을 따른다),
 *   - 플레이어 파일의 React 표면 영역을 영속화가 아닌 "다음에 재생할 광고
 *     선택" 시맨틱에 집중하게 한다.
 *
 * ## 스토리지 형태
 *
 * 디바이스별 localStorage 키 하나. 동일 Android 기기에 두 디바이스가 있는
 * 경우(실제로는 드물지만 동일 브라우저 프로필을 가리키는 다중 WebView의 개발
 * 환경에서 가능)에도 서로 덮어쓰지 못하도록 스코프를 지정:
 *
 *   localStorage["adsignage:dailyCount:<deviceId>"] =
 *     JSON.stringify({
 *       date:   "YYYY-MM-DD",        // 디바이스 로컬 벽시계 날짜
 *       counts: { "<adId>": number, ... }
 *     })
 *
 * 래퍼 객체는 *항상* 카운트가 누적된 날짜를 운반한다. 매 로드(및 매 증가)
 * 시 오늘의 로컬 날짜와 비교해 다르면 bag을 `{}`로 리셋한다. 이것이 날짜
 * 롤오버 메커니즘의 전부다 — 타이머도, 자정 wake-up 작업도 없고, 잠자던
 * WebView가 롤오버를 놓칠 위험도 없다.
 *
 * ## 로컬 날짜를 쓰는 이유(UTC 아님)
 *
 * 운영자가 설정한 일일 윈도우(`startTime`/`endTime`, "HH:mm")는 벽시계 기반
 * — `playlist.ts: parseHhMmToMinutes` 참조. "저녁 17:00–21:00, 200회/일"로
 * 설정한 한국 식당 운영자는 200이 *한국 자정*에 리셋되기를 기대하지, 09:00
 * KST(UTC 자정)가 아니다. `getFullYear/getMonth/getDate`를 읽으면 사용자가
 * 머무는 디바이스 타임존이 반영되어 술어의 시맨틱과 일치한다.
 *
 * ## 상한 시맨틱
 *
 *   - `cap == null`/`undefined`  → 무제한; 절대 필터 아웃하지 않음.
 *   - `cap <= 0`                 → 절대 재생하지 않음; 즉시 필터 아웃.
 *   - `cap >= 1`                 → 오늘 최대 `cap`회 재생; `count >= cap`이면
 *                                  필터 아웃.
 *
 * 검사는 `count >= cap`이지 `> cap`이 아니므로, N번째 재생은 허용되고
 * (N+1)번째는 억제된다. 증가는 완료된 재생마다 정확히 한 번 발생한다
 * (플레이어의 `<video onEnded>` 핸들러).
 *
 * ## SSR / 비브라우저 안전성
 *
 * Next.js는 빌드 시 서버에서 플레이어 라우트를 프리렌더한다. Node에는
 * `localStorage`가 없다 — 모든 접근은 `typeof window`로 가드되며 인메모리
 * 상태로 폴백한다. JSON 파싱 실패(이전 버전의 손상된 스토리지)는 throw 대신
 * 새 빈 bag으로 붕괴되므로, 브라우저에 stale 데이터가 있는 개발자가 플레이어를
 * 브릭할 수 없다.
 */

import type { PlaylistAd } from "./playlist";

/* ------------------------------------------------------------------- types */

/**
 * 영속화 형태이자 플레이어 내부에서 사용되는 런타임 형태. 날짜를 카운트와
 * 함께 운반하므로 롤오버 검사는 별도 상태를 동기화하는 대신 단일 비교로
 * 끝난다.
 */
export interface DailyCounters {
  /**
   * [counts]가 누적된 로컬 타임존 캘린더 일자.
   * 형식: `YYYY-MM-DD`. 매 읽기/쓰기마다 [todayKey]와 비교해 자정 롤오버를
   * 감지한다.
   */
  date: string;
  /**
   * 광고 id를 키로 하는 오늘까지의 재생 횟수. 키가 없으면 `0`으로 취급 —
   * 한 번도 재생하지 않은 광고에 대해 슬롯을 미리 할당하지 않는다.
   */
  counts: Record<string, number>;
}

/* ------------------------------------------------------------ date helpers */

/**
 * 1자리 또는 2자리 정수를 2글자로 패딩한다. 구형 JS 엔진을 타깃하는
 * vendored-impl 테스트 미러와 친화적이도록 `String.padStart` 대신 직접 구현.
 */
function pad2(n: number): string {
  if (n < 10) return "0" + String(n);
  return String(n);
}

/**
 * [date]를 로컬 캘린더 `YYYY-MM-DD` 문자열로 포맷한다. 로컬 — 즉 디바이스의
 * `getFullYear/getMonth/getDate` — 이므로 롤오버가 운영자의 로컬 자정에
 * 발생하며, [parseHhMmToMinutes] / [isAdActive]가 이미 사용 중인 벽시계
 * 시맨틱과 일치한다.
 *
 * 단위 테스트 미러와 React 상태 오버레이가 동일한 표준 문자열을 읽을 수
 * 있도록 export한다.
 */
export function localDateKey(date: Date = new Date()): string {
  const y = date.getFullYear();
  const m = pad2(date.getMonth() + 1); // getMonth는 0-인덱스
  const d = pad2(date.getDate());
  return y + "-" + m + "-" + d;
}

/** 오늘을 로컬 캘린더 형태로, 호출자들이 표류하지 않도록 한 곳에서 제공. */
export function todayKey(): string {
  return localDateKey(new Date());
}

/* ----------------------------------------------------------- cap helpers */

/**
 * 카운터 bag이 주어졌을 때 [adId]의 오늘까지 재생 수. 키가 없으면 → 0.
 * 모든 호출자가 상한 평가까지 함께 원하므로 단독 export하지 않는다 —
 * [getRemaining] / [isAdCapped] / [filterUnderCap] 참조.
 */
function playsForAd(counts: Record<string, number>, adId: string): number {
  const v = counts[adId];
  if (typeof v !== "number" || !Number.isFinite(v) || v < 0) return 0;
  return Math.floor(v);
}

/**
 * 현재 카운터를 고려해 [ad]가 오늘 몇 번 더 재생될 수 있는지 반환한다.
 *
 *   - `null`              무제한 광고(상한 없음/null).
 *   - `0`                 상한에 도달한 광고(`cap <= 0`도 포함).
 *   - 양의 정수            남은 재생이 있는 광고.
 *
 * `null` 센티넬은 의도적이다 — `Infinity`는 차감 산술(예: `remaining - 1`을
 * 계산하는 UI 오버레이)을 조용히 통과해버리고, 운영자 표면은 "무제한"을
 * "∞"가 아닌 별개의 상태로 렌더링해야 한다. 순수 헬퍼.
 */
export function getRemaining(
  ad: Pick<PlaylistAd, "adId" | "dailyCount">,
  counts: Record<string, number>,
): number | null {
  const cap = normaliseCap(ad.dailyCount);
  if (cap === null) return null;
  const used = playsForAd(counts, ad.adId);
  const remaining = cap - used;
  return remaining > 0 ? remaining : 0;
}

/**
 * [ad]가 일일 상한에 도달했는가? "남은 재생이 있다"의 역. 무제한 광고
 * (`cap == null`)는 절대 상한에 도달하지 않는다. `cap <= 0` 광고는 첫날부터
 * 즉시 상한 도달 — 운영자가 사실상 음소거한 상태 — 이지만, 그런 비정상
 * 설정에서도 크래시하지 않는다.
 */
export function isAdCapped(
  ad: Pick<PlaylistAd, "adId" | "dailyCount">,
  counts: Record<string, number>,
): boolean {
  const r = getRemaining(ad, counts);
  if (r === null) return false; // 무제한
  return r <= 0;
}

/**
 * [ads]를 오늘 아직 재생 여유가 있는 항목으로 좁힌다. 상한 필터 적용 이후에도
 * 라운드 로빈 AC가 플레이리스트 순서대로 계속 순회할 수 있도록 순서를
 * 보존한다. 순수 / React 없음.
 */
export function filterUnderCap<T extends Pick<PlaylistAd, "adId" | "dailyCount">>(
  ads: T[],
  counts: Record<string, number>,
): T[] {
  return ads.filter((ad) => !isAdCapped(ad, counts));
}

/**
 * 와이어상의 `dailyCount` 필드를 타입드 상한으로 강제 변환한다.
 *
 *   - missing/null/undefined → null  (무제한)
 *   - non-integer / NaN      → null  (잘못된 페이로드로 광고를 블랙홀에
 *                                     빠뜨리지 않고 무제한으로 격하)
 *   - 음수 / 0                → 0     (즉시 상한 도달)
 *   - 양의 정수               → 그 정수
 *
 * 순수 헬퍼이며 테스트 미러를 위해 export.
 */
export function normaliseCap(cap: number | null | undefined): number | null {
  if (cap === null || cap === undefined) return null;
  if (typeof cap !== "number" || !Number.isFinite(cap)) return null;
  if (cap <= 0) return 0;
  return Math.floor(cap);
}

/* ----------------------------------------------------------- state helpers */

/**
 * [state]에 날짜 롤오버를 적용한다: `date`가 [today]가 아니면 카운트를
 * 버리고 새 날짜를 찍는다. 롤오버 시 새 객체를 반환하여(React `useState`
 * 세터가 인지하도록) 날짜가 이미 일치할 때는 동일 참조를 반환한다(무관한
 * 재렌더가 churn되지 않도록).
 */
export function rolloverIfNewDay(
  state: DailyCounters,
  today: string = todayKey(),
): DailyCounters {
  if (state.date === today) return state;
  return { date: today, counts: {} };
}

/**
 * [adId]의 카운트가 1 증가된 새 [DailyCounters]를 반환한다.
 * 날짜 롤오버 인식: 입력의 날짜가 stale하면 증가는 새 같은 날짜 bag에
 * 들어가, 자정을 넘어선 재생이 어제 카운터에 조용히 들어가지 않는다.
 *
 * 순수(I/O 없음). localStorage 쓰기는 React 계층(`useDailyCounters` /
 * `PlayerClient`)이 담당하므로 이 헬퍼는 손쉽게 테스트 가능하다.
 */
export function incrementCount(
  state: DailyCounters,
  adId: string,
  today: string = todayKey(),
): DailyCounters {
  if (!adId) return state;
  const fresh = rolloverIfNewDay(state, today);
  const next = { ...fresh.counts };
  next[adId] = playsForAd(fresh.counts, adId) + 1;
  return { date: fresh.date, counts: next };
}

/** 빈 같은 날짜 카운터 bag을 만든다 — 초기 상태로 사용. */
export function emptyCounters(today: string = todayKey()): DailyCounters {
  return { date: today, counts: {} };
}

/* ------------------------------------------------------------- persistence */

/** 스토리지 키 생성기. 디바이스별이라 여러 WebView가 공존할 수 있다. */
export function storageKey(deviceId: string): string {
  return "adsignage:dailyCount:" + deviceId;
}

/**
 * `localStorage` 사용 가능 여부를 감지한다. SSR(Next.js 빌드)에는 `window`가
 * 없다. 쿠키/스토리지가 비활성화된 WebView는 접근 시 throw한다. 두 경우
 * 모두 운영자에게 에러를 노출하지 않고 "영속화 없음; 인메모리만"으로
 * 붕괴된다.
 */
function hasStorage(): boolean {
  try {
    if (typeof window === "undefined") return false;
    if (!window.localStorage) return false;
    return true;
  } catch {
    return false;
  }
}

/**
 * [deviceId]의 카운터 bag을 로드(및 날짜 롤오버)한다. 항상 같은 날짜의
 * [DailyCounters]를 반환하며 절대 throw하지 않는다. 없거나, 파싱 불가하거나,
 * 스키마가 일치하지 않는 페이로드는 오늘 날짜로 찍힌 새 빈 bag으로 붕괴된다.
 */
export function loadCounters(deviceId: string): DailyCounters {
  const today = todayKey();
  if (!deviceId) return emptyCounters(today);
  // 클라 측 dailyCount 영구화는 *서버 측 play_events 의 daily-cap 카운트* 와
  // 이중 카운팅을 만들고, localStorage 가 stale 한 채로 누적되면 *살아있는*
  // 디바이스가 cap 도달해 영원히 standby 만 띄우는 stuck 버그를 만들었다.
  // 권위 있는 daily-cap 은 서버에 두고, 클라 측은 *세션 단위* 메모리 카운터로
  // 폭주 방지 정도만 책임지도록 영구화 제거 — 매 mount 시 빈 bag 으로 시작.
  return emptyCounters(today);
}

/**
 * [deviceId]의 [state]를 영속화한다. 최선 노력 — 할당량 에러나 스토리지
 * 비활성 WebView는 warn 레벨로 로깅하고 swallow한다. 세션 나머지 동안에는
 * 인메모리 상태가 권위 있는 상태로 유지된다.
 */
export function saveCounters(
  deviceId: string,
  state: DailyCounters,
): void {
  // dailyCount 영구화 비활성 (loadCounters 주석 참조). 호환성을 위해
  // 함수 시그니처는 유지 — 호출자(PlayerClient) 코드 변경 0.
  // 또한 옛 누적 데이터가 남아 있다면 한 번에 정리.
  if (!deviceId) return;
  if (!hasStorage()) return;
  try {
    window.localStorage.removeItem(storageKey(deviceId));
  } catch {
    /* 시크릿 모드 등 — 무시 */
  }
  void state; // 미사용 — emptyCounters 가 권위 상태
}

/**
 * 임의의 `JSON.parse` 결과를 잘 타입드된 [DailyCounters]로 강제 변환하거나,
 * 형태 복구가 불가능하면 `null`을 반환한다. 스토리지는 영속화 경계이므로
 * 방어적이다 — 이전 앱 버전, 수동 편집, 손상된 프로필 등 어떤 것이든 거기에
 * 들어갈 수 있다. 테스트 미러를 위해 export.
 */
export function normaliseStoredCounters(value: unknown): DailyCounters | null {
  if (!value || typeof value !== "object") return null;
  const obj = value as Record<string, unknown>;
  const date = typeof obj.date === "string" ? obj.date : "";
  if (!/^\d{4}-\d{2}-\d{2}$/.test(date)) return null;
  const rawCounts = obj.counts;
  if (!rawCounts || typeof rawCounts !== "object") return null;
  const counts: Record<string, number> = {};
  for (const [k, v] of Object.entries(rawCounts as Record<string, unknown>)) {
    if (typeof v !== "number" || !Number.isFinite(v) || v < 0) continue;
    counts[k] = Math.floor(v);
  }
  return { date, counts };
}

/**
 * node:test 미러용 내부 export. 공개 API의 일부가 아님.
 */
export const __test__ = {
  pad2,
  playsForAd,
};
