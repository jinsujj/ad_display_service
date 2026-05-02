/**
 * 플레이어 플레이리스트 API 표면.
 *
 * 와이어 계약(Spring Boot 백엔드, 형제 Sub-AC 소관):
 *
 *   GET /api/devices/{id}/playlist
 *     -> 200 OK, application/json
 *        {
 *          "deviceId":     string,
 *          "restaurantId": string?,                  // 매핑 없으면 null
 *          "ads": [
 *            {
 *              "adId":         string,
 *              "title":        string,
 *              "videoUrl":     string,               // 절대 URL 또는 경로
 *              "scheduleId":   string,
 *              "startTime":    string?,              // "HH:mm"
 *              "endTime":      string?,              // "HH:mm"
 *              "dailyCount":   number?
 *            },
 *            ...
 *          ],
 *          "fetchedAt": string                       // ISO-8601
 *        }
 *
 * 이 엔드포인트는 `SecurityConfig`의 허용 목록에 있으며(`/api/devices/*\/playlist`
 * 매처의 주석 참조), 형제 Sub-AC에서 구현 중이다. 이 모듈은 엔드포인트가
 * 아직 없는 경우에도 견고하다 — 호출자(플레이어 페이지)는 [ApiError]를
 * 캐치하고 스플래시를 렌더링하므로, SSE 기반 리로드는 항상 안전하게 착지할
 * 곳을 갖는다.
 *
 * 별도 파일로 둔 이유(`lib/devices.ts` 확장이 아닌):
 *   devices 모듈은 관리자 측이고, playlist는 플레이어 측이다. 둘은 독립적으로
 *   진화하며, 플레이어가 관리자 전용 타입을 끌어와서는 안 되고 그 반대도
 *   마찬가지다.
 */

import { apiFetch, apiUrl, ApiError } from "./api";

/** 디바이스 현재 플레이리스트의 단일 광고 항목. */
export interface PlaylistAd {
  adId: string;
  title: string;
  /**
   * `<video>` 요소가 그대로 `src`에 사용할 수 있는 해소된 절대 URL.
   * 백엔드가 상대 경로를 반환하면 [fetchPlaylist]가 API 베이스 URL과 결합해
   * 해소하므로, WebView는 추가 처리 없이 재생할 수 있다.
   */
  videoUrl: string;
  scheduleId: string;
  startTime?: string | null;
  endTime?: string | null;
  dailyCount?: number | null;
}

/** 단일 디바이스용 전체 플레이리스트 페이로드. */
export interface DevicePlaylist {
  deviceId: string;
  restaurantId: string | null;
  ads: PlaylistAd[];
  fetchedAt: string;
}

type RawPlaylistAd = Partial<PlaylistAd> & {
  id?: string;
  url?: string;
  src?: string;
};

type RawPlaylist = {
  deviceId?: string;
  restaurantId?: string | null;
  ads?: RawPlaylistAd[] | null;
  items?: RawPlaylistAd[] | null;
  fetchedAt?: string;
};

/**
 * [deviceId]의 현재 플레이리스트를 가져온다. 플레이어 페이지의 모든 SSE
 * MAPPING_CHANGED / PLAYLIST_UPDATE 이벤트 이후 재실행된다(`hooks/usePlayerSse.ts`
 * 참조).
 *
 * 결과는 정규화되어, 해커톤 셋업 중 발생할 수 있는 소소한 백엔드 형태
 * 차이와 무관하게 플레이어가 플레이리스트를 일관되게 다룰 수 있다.
 */
export async function fetchPlaylist(
  deviceId: string,
): Promise<DevicePlaylist> {
  if (!deviceId) throw new Error("deviceId is required");
  const raw = await apiFetch<RawPlaylist>(
    `/api/devices/${encodeURIComponent(deviceId)}/playlist`,
  );
  return normalisePlaylist(deviceId, raw);
}

function normalisePlaylist(
  deviceId: string,
  raw: RawPlaylist | null | undefined,
): DevicePlaylist {
  const adsSource = Array.isArray(raw?.ads)
    ? raw!.ads!
    : Array.isArray(raw?.items)
      ? raw!.items!
      : [];
  const ads = adsSource
    .map(normaliseAd)
    .filter((a): a is PlaylistAd => a !== null);
  return {
    deviceId: raw?.deviceId ?? deviceId,
    restaurantId: raw?.restaurantId ?? null,
    ads,
    fetchedAt: raw?.fetchedAt ?? new Date().toISOString(),
  };
}

/**
 * SSE 빠른 경로(AC 60201 Sub-AC 1)를 위한 [normalisePlaylist]의 공개 API
 * 대응 함수.
 *
 * `PLAYLIST_UPDATE` SSE 이벤트가 와이어에서 인라인 `playlist`를 운반하면,
 * 플레이어 훅은 그 느슨한 타입 객체를 이 함수로 넘겨 React 상태에 커밋하기
 * 전에 표준 `DevicePlaylist`로 강제 변환한다. HTTP 재조회 경로와 정확히
 * 동일한 정규화 규칙을 재사용하므로, 어떤 채널로 플레이리스트가 도착했든
 * 재생은 동일하다.
 *
 * 입력이 plain object가 아니면 throw한다 — 호출자(PlayerClient)는 이를
 * 캐치하고 재조회로 폴백한다.
 */
export function normaliseInlinePlaylist(
  deviceId: string,
  raw: unknown,
): DevicePlaylist {
  if (!raw || typeof raw !== "object") {
    throw new Error("inline playlist must be a JSON object");
  }
  return normalisePlaylist(deviceId, raw as RawPlaylist);
}

function normaliseAd(raw: RawPlaylistAd): PlaylistAd | null {
  const adId = raw.adId ?? raw.id ?? "";
  if (!adId) return null;
  const rawUrl = raw.videoUrl ?? raw.url ?? raw.src ?? "";
  if (!rawUrl) return null;
  return {
    adId,
    title: raw.title ?? "",
    videoUrl: resolveVideoUrl(rawUrl),
    scheduleId: raw.scheduleId ?? "",
    startTime: raw.startTime ?? null,
    endTime: raw.endTime ?? null,
    dailyCount: raw.dailyCount ?? null,
  };
}

/**
 * `<video src>`가 WebView가 해소할 수 있는 형태인지 보장한다.
 *
 * 케이스:
 *   - "https://..." / "http://..." → 그대로 반환.
 *   - "/api/videos/abc.mp4"        → API 베이스 URL과 결합.
 *   - "videos/abc.mp4"             → API 베이스 URL과 앞쪽 "/"를 추가해 결합.
 *
 * 플레이어는 Next.js origin(예: https://stream.owl-dev.me)에서 실행되지만,
 * 비디오는 Spring 백엔드(로컬 개발 시 다른 호스트일 수 있음)에서 제공되므로
 * 해소가 필요하다.
 */
function resolveVideoUrl(url: string): string {
  if (/^https?:\/\//i.test(url)) return url;
  return apiUrl(url);
}

/* ---------------------------------------------------------- AC 9 helpers
 *
 * AC 9 — "광고는 예약된 시간 윈도우 내에서만 재생된다".
 *
 * 위 와이어 계약은 이미 광고별 `startTime` / `endTime`을 운반한다(HH:mm
 * 문자열, 디바이스 로컬 벽시계 시맨틱 — 백엔드 `Ad.kt` / `AdScheduleDtos.kt`
 * 참조). 백엔드의 CHECK 제약 `ck_ads_time_window`가 `endTime > startTime`을
 * 보장하므로, 윈도우는 자정 랩어라운드를 모델링하지 않고 단순 반열린 구간
 * `[start, end)`로 다룰 수 있다.
 *
 * 이 규칙은 플레이어에서 강제된다 — seed에 따르면:
 *   "모든 재생/스케줄/SSE 로직은 네이티브 Android가 아닌 Next.js 플레이어
 *    페이지에 있다."
 *
 * 두 개의 순수 헬퍼는 규칙을 React 컴포넌트와 독립적으로 테스트 가능하게
 * 하며, 라운드 로빈 AC가 이미 순회하는 동일한 플레이리스트 형태를 계속
 * 사용할 수 있게 한다(좁혀진 목록만 넘겨주면 된다).
 */

/**
 * `HH:mm` 시각 문자열을 자정 이후 분 단위 정수로 파싱한다.
 *
 * 형식 오류 입력에는 `null`을 반환한다 — 빈 문자열, 누락 필드, 범위 밖의
 * 시/분, 비숫자 조각 모두 포함. 호출자(예: [isAdActive])는 `null` 결과를
 * "이쪽 끝에는 스케줄 없음"으로 간주하고 보수적으로 재생을 허용함으로써,
 * 서버의 포맷팅 버그 때문에 광고가 영구적으로 가려지는 일을 막는다.
 *
 * 플레이어의 상태 오버레이가 라이브 필터 결과와 함께 윈도우를 사람이
 * 읽기 쉬운 형태로 렌더링할 수 있도록 export한다.
 */
export function parseHhMmToMinutes(value: string | null | undefined): number | null {
  if (!value) return null;
  // "HH:mm"만 허용 — 백엔드는 정확히 이 패턴으로 직렬화한다.
  // "H:mm", "HHmm", "HH:mm:ss", 끝쪽 공백은 모두 거부하여 잘못된 형식의
  // 윈도우를 조용히 받아들이지 않도록 한다.
  const match = /^([0-9]{2}):([0-9]{2})$/.exec(value);
  if (!match) return null;
  const h = Number(match[1]);
  const m = Number(match[2]);
  if (!Number.isFinite(h) || !Number.isFinite(m)) return null;
  if (h < 0 || h > 23) return null;
  if (m < 0 || m > 59) return null;
  return h * 60 + m;
}

/**
 * [Date]를 디바이스 로컬 타임존(JS 런타임의 타임존 — WebView에서는 디바이스
 * 타임존)에서 [parseHhMmToMinutes]가 사용하는 것과 같은 자정 이후 분 단위
 * 정수로 변환한다.
 *
 * `toLocaleTimeString` 파싱이 아닌 이 방식을 쓴 이유:
 *   - 문자열을 절대 할당하지 않아, 벽시계 틱이 살아있는 동안 플레이어의
 *     매 렌더에서 호출해도 비용이 작다;
 *   - 로케일 독립이므로, 한국어/영어 Android 디바이스에서 동일하게 동작한다;
 *   - 30분 단위 타임존(예: Asia/Kabul)을 피해, 단순한
 *     `toISOString().slice(11, 16)` 접근법을 깨뜨리지 않는다.
 */
export function dateToLocalMinutes(date: Date): number {
  return date.getHours() * 60 + date.getMinutes();
}

/**
 * AC 9 술어 — [ad]가 현재 스케줄 재생 윈도우 안에 있는가?
 *
 * 시맨틱:
 *   - 윈도우는 `[startTime, endTime)` — 시작은 포함, 종료는 제외.
 *     일반적인 "저녁 서비스 17:00..21:00 동안 광고" 의도와 일치한다:
 *     21:00에는 윈도우가 막 종료된 상태다.
 *   - 벽시계 시각은 디바이스 로컬 타임존의 [now]에서 가져온다. 기본값은
 *     새 `new Date()`이므로 단순한 경우 호출자는 인자를 생략할 수 있고,
 *     테스트는 고정 시각을 주입할 수 있다.
 *   - 어느 한쪽 경계가 누락되었거나 파싱 불가능하면, 그쪽 윈도우는 제약
 *     없는 것으로 취급한다. 두 경계 모두 누락이면 "항상 활성" — null을
 *     생성하는 서버 버그가 고객의 전체 스케줄을 블랙홀에 빠뜨리지 않도록
 *     fail-open. 실제로는 백엔드의 자체 검증(`UpdateAdScheduleRequest`)이
 *     두 필드가 모두 존재함을 보장한다.
 *
 * 순수 / React 의존성 없음 — [filterActiveAds]와 함께 향후 jest 스위트에서
 * 실행할 수 있다.
 */
export function isAdActive(ad: PlaylistAd, now: Date = new Date()): boolean {
  const startMin = parseHhMmToMinutes(ad.startTime);
  const endMin = parseHhMmToMinutes(ad.endTime);
  if (startMin === null && endMin === null) return true;
  const nowMin = dateToLocalMinutes(now);
  if (startMin !== null && nowMin < startMin) return false;
  if (endMin !== null && nowMin >= endMin) return false;
  return true;
}

/**
 * AC 9 — 플레이리스트의 `ads` 목록을 현재 스케줄 윈도우 안에 있는 항목으로만
 * 좁힌다. 순서는 보존되므로 라운드 로빈 AC는 플레이리스트 순서대로 계속
 * 순회할 수 있다. 활성 세트는 원본 배열을 라이브 벽시계로 필터링한 안정된
 * 뷰일 뿐이다.
 *
 * 호출자 측의 한 줄 인라인 필터가 아닌 별도 함수로 둔 이유:
 *   - 플레이어 페이지가 상태 오버레이의 "active n of m"을 재생 경로와 동일한
 *     진실의 출처로 표시할 수 있게 한다.
 *   - 술어의 `now` 주입 지점을 한 곳에 둠으로써, 다른 시간 소스(예: 서버
 *     시각)로의 교체가 쉬워진다 — 플레이어 컴포넌트를 뒤지지 않고도 가능.
 */
export function filterActiveAds(
  ads: PlaylistAd[],
  now: Date = new Date(),
): PlaylistAd[] {
  return ads.filter((ad) => isAdActive(ad, now));
}

/** 플레이어 페이지가 단일 모듈에서만 import할 수 있도록 편의를 위해 재export. */
export { ApiError };
