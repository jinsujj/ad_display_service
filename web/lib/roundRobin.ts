/**
 * 디바이스 플레이리스트를 위한 라운드 로빈 회전 알고리즘.
 *
 * AC 8 소관: "한 디바이스의 여러 광고는 라운드 로빈 알고리즘으로 회전한다".
 *
 * ## 여기서 라운드 로빈의 의미
 *
 * 길이 N의 순서 있는 플레이리스트 `ads = [A, B, C, …]`가 주어질 때, 디바이스는
 * 사이클당 각 광고를 한 번씩 끝까지 재생한 뒤 다음 슬롯으로 진행하고, 마지막
 * 이후에는 처음으로 돌아간다:
 *
 *   tick:  0   1   2   3   4   5   6   …
 *   slot:  0   1   2   0   1   2   0   …
 *   ad:    A   B   C   A   B   C   A   …
 *
 * 즉: `slot(tick+1) = (slot(tick) + 1) mod N`. 이 알고리즘은 각 광고가 *언제*
 * 끝나는지와 무관하다 — 백엔드가 내보낸 순서대로 모든 광고가 사이클당 정확히
 * 한 번 재생된다.
 *
 * ## 별도 모듈로 둔 이유(`PlayerClient`에 인라인하지 않은 이유)
 *
 * `PlayerClient`는 DOM 이벤트(`onEnded`, `onError`)와 SSE 기반 플레이리스트
 * 재조회를 회전 상태에 연결하는 React 표면이다. 회전 산술을 그 I/O와 섞으면
 * 알고리즘을 추론하기 어렵고, DOM 또는 EventSource 스텁 없이는 단위 테스트가
 * 불가능하다.
 *
 * 순수하고 부수효과 없는 모듈로 추출하면:
 *   - 감사자가 end-to-end로 읽을 수 있는 한 곳에 라운드 로빈 계약을 모은다,
 *   - 데모 오버레이와 테스트용으로 `simulateRotation`(다음 N개 선택의 결정적
 *     미리보기)을 노출하기가 간단해진다,
 *   - 아래의 모든 엣지 케이스에 대해 알고리즘을 고정시키는 `node --test`
 *     스위트를 운용할 수 있어, 향후 리팩터로 wrap-around 동작이 미묘하게
 *     깨지면 라이브 데모가 아닌 CI에서 실패하게 된다.
 *
 * ## 알고리즘이 다루는 엣지 케이스
 *
 *   1. **빈 플레이리스트**(`adsLength === 0`): 모든 연산은 no-op이며 `0`을
 *      반환한다. 호출자(`PlayerClient`)는 "no ads scheduled" 스플래시를
 *      렌더링하고 비디오 재생을 시도하지 않으므로 인덱스 값은 무의미하지만,
 *      일시적인 빈 상태가 상태 오버레이에 `NaN`이나 stale 값으로 노출되지
 *      않도록 결정적으로 유지한다.
 *
 *   2. **단일 광고 플레이리스트**(`adsLength === 1`): `advance`는 항상 `0`을
 *      반환. (이 경우 `PlayerClient`는 end/restart 플리커를 피하기 위해
 *      `<video loop>`로 단락 처리하지만, `loop`가 어떤 이유로든 꺼지더라도
 *      알고리즘은 올바르게 동작한다 — `ended` 이벤트가 같은 슬롯으로 계속
 *      진행할 뿐이다.)
 *
 *   3. **라이브 인덱스 아래에서 플레이리스트가 줄어듦**(`currentIndex >=
 *      adsLength`): `PLAYLIST_UPDATE` 이벤트는 회전 중에 광고를 제거할 수
 *      있다. `clampAfterShrink`가 인덱스를 `0`으로 되돌려, 다음 `ended` 전까지
 *      아무것도 렌더링되지 않는 대신 다음 페인트가 유효한 광고를 고르도록 한다.
 *
 *   4. **라이브 인덱스 아래에서 플레이리스트가 늘어남**: 새 광고들은 배열의
 *      뒤쪽에 위치한다. 현재 인덱스는 여전히 유효(이미 범위 내)하므로
 *      재생 중인 광고를 그대로 재생하고, 이후 `advance`에서 새 광고들이
 *      자연스럽게 방문된다. "늘어나면 0으로 재시작"하는 로직은 의도적으로
 *      *없다* — 그것은 데모를 뒤로 튀게 만들 것이다.
 *
 *   5. **같은 형태의 플레이리스트로 교체**(스케줄 광고 수가 우연히 같은
 *      식당으로의 매핑 변경): 호출자는 플레이리스트 세대 id(`PlayerClient`의
 *      `loadedAt`)를 키로 `advance`와 `reset()`을 짝지어 둔다. 그러므로
 *      `clampAfterShrink`가 발동하지 않더라도 재매핑은 항상 슬롯 0에서
 *      시작된다.
 *
 *   6. **음수 인덱스 / NaN**: 방어적 — 모든 헬퍼는 잘못된 입력을 `0`으로
 *      강제 변환한다. 재생을 유지하기 위해 stale하거나 손상된 상태 값을
 *      절대 신뢰하지 않는다.
 *
 * ## 이 모듈이 책임지지 *않는* 것
 *
 *   - **스케줄 윈도우 필터링**(HH:mm + dailyCount). 백엔드 플레이리스트
 *     엔드포인트의 책임. 플레이리스트가 이 모듈에 도달할 무렵에는 `ads`가
 *     이미 회전해야 할 활성 세트다.
 *
 *   - **디바이스 간 공정성**(예: 같은 식당 내 여러 디바이스에 일일 카운트
 *     재생을 분배). 백엔드 플레이리스트 계산의 책임. 이 모듈은 디바이스
 *     단위다.
 *
 *   - **고장난 비디오 건너뛰기**. `PlayerClient`가 `onError → advance`로
 *     처리한다. 이 모듈은 콘텐츠에 무관하다.
 *
 * ## 알고리즘 복잡도
 *
 * 모든 연산은 `O(1)` — 라운드 로빈은 설계상 가능한 가장 저렴한 회전
 * 알고리즘이다. `simulateRotation`은 `O(steps)`이며, 짧은 미리보기(≤ 32개
 * 항목)에 의도된 것이지 긴 트레이스를 구체화하기 위함이 아니다.
 */

/** 호출자가 "지금 재생할 광고 없음"으로 취급하는 센티넬 값. */
export const NO_AD_INDEX = -1 as const;

/**
 * 임의의 값을 비음수 유한 정수로 강제 변환하고, 그렇지 않으면 `0`을 반환한다.
 * 손상된 React 상태 값(예: 트리 외부 에러로 인한)이 `NaN`을 `<video>` 선택에
 * 전파하지 않도록 경계에서 사용한다.
 */
function safeNonNegativeInt(value: unknown): number {
  if (typeof value !== "number") return 0;
  if (!Number.isFinite(value)) return 0;
  if (value < 0) return 0;
  return Math.floor(value);
}

/**
 * 현재 슬롯이 끝난 후 재생되어야 할 슬롯 인덱스를 반환한다.
 *
 * - `adsLength <= 0`         → `0`  (빈 플레이리스트; 인덱스는 무의미)
 * - `adsLength === 1`        → `0`  (단일 광고: 회전 불필요)
 * - 그 외                     → `(currentIndex + 1) mod adsLength`
 *
 * `PlayerClient`에서 `<video>`의 `ended` 이벤트에 연결된다. 인라인
 * `setCurrentIndex(i => …)` 대신 순수 함수로 구현하여, 아래 단위 테스트에서
 * 동작을 고정할 수 있게 한다.
 */
export function advance(currentIndex: number, adsLength: number): number {
  const len = safeNonNegativeInt(adsLength);
  if (len <= 0) return 0;
  if (len === 1) return 0;
  const i = safeNonNegativeInt(currentIndex);
  return (i + 1) % len;
}

/**
 * 회전 인덱스를 플레이리스트의 시작으로 리셋한다. 플레이리스트 세대가
 * 변할 때(초기 조회, 매핑 변경, 인-플레이스 플레이리스트 업데이트)마다
 * `PlayerClient`가 호출하므로, 재매핑은 항상 새 식당의 첫 광고에서 시작된다.
 *
 * 표준 "시작" 값(`0`)을 반환한다 — 상수가 아닌 함수로 제공함으로써, 호출
 * 지점을 모두 건드리지 않고도 향후 다른 정책(예: 짧은 단절 후
 * resume-where-you-left-off)으로 교체할 수 있다.
 */
export function reset(): number {
  return 0;
}

/**
 * 범위를 벗어났을 수 있는 인덱스를 현재 플레이리스트의 경계로 clamp한다.
 * 멱등: 이미 유효한 인덱스에 호출해도 같은 값을 반환한다.
 *
 * 라이브 회전 아래에서 플레이리스트 길이가 변할 때 트리거된다(가장 흔하게는
 * 광고를 제거하는 `PLAYLIST_UPDATE`). 축소로 인덱스가 새 꼬리를 넘었으면
 * `0`으로 되돌리고, 아니면 진행 중인 광고를 유지한다. `adsLength-1`로 clamp
 * 하지 않는 이유는, 플레이어가 이미 이번 사이클의 `currentIndex`를 소비했기
 * 때문이다 — 새 첫 광고에서 다시 시작하는 것만이 뒤로 점프하거나 검은 프레임
 * 없이 동작하는 유일한 방식이다.
 */
export function clampAfterShrink(
  currentIndex: number,
  adsLength: number,
): number {
  const len = safeNonNegativeInt(adsLength);
  if (len <= 0) return 0;
  const i = safeNonNegativeInt(currentIndex);
  if (i >= len) return 0;
  return i;
}

/**
 * 플레이어가 현재 렌더링해야 할 슬롯을 해소한다. `% adsLength` 가드(이펙트
 * 실행 사이의 일시적 범위 이탈 상태에 대한 모듈로)를 빈 상태용 센티넬과
 * 결합한다.
 *
 * 플레이리스트가 비었으면 `NO_AD_INDEX` (-1)를 반환하여, 호출자가 `0`이 "첫
 * 광고"인지 "광고 없음"인지 추측하지 않고 선언적으로 분기할 수 있게 한다
 * (`safeIndex === NO_AD_INDEX` ⇒ 스플래시 렌더링).
 *
 * 단순한 `currentIndex % adsLength` 형태는 "빈 플레이리스트"와 "비어있지 않은
 * 플레이리스트의 첫 광고" 모두에 대해 `0`을 강제하는데, 이는 캐러셀 위젯에서
 * 오랫동안 off-by-one 버그의 원천이었다.
 */
export function safeIndex(currentIndex: number, adsLength: number): number {
  const len = safeNonNegativeInt(adsLength);
  if (len <= 0) return NO_AD_INDEX;
  const i = safeNonNegativeInt(currentIndex);
  return i % len;
}

/**
 * [startIndex]에서 시작해 다음 [steps]개의 선택을 구체화한다.
 * 순수 헬퍼 — 다음 용도에 유용:
 *   - 데모 상태 오버레이(회전의 다음 3개 광고를 미리보기),
 *   - 임의 길이에 대한 wrap-around 동작을 고정하는 단위 테스트,
 *   - 운영자 로그 라인(`"upcoming: A → B → C → A"`).
 *
 * 길이 제한: 호출자는 작은 `steps`(≤ 32)를 전달해야 한다. 빈 플레이리스트에는
 * `[]`을 반환한다. 첫 요소는 `safeIndex(startIndex, adsLength)`이므로 트레이스가
 * 플레이어가 현재 표시하는 내용과 일치한다.
 */
export function simulateRotation(
  startIndex: number,
  adsLength: number,
  steps: number,
): number[] {
  const len = safeNonNegativeInt(adsLength);
  if (len <= 0) return [];
  const stepCount = safeNonNegativeInt(steps);
  if (stepCount <= 0) return [];
  const out: number[] = new Array(stepCount);
  let i = safeIndex(startIndex, len);
  if (i === NO_AD_INDEX) return [];
  for (let k = 0; k < stepCount; k += 1) {
    out[k] = i;
    i = (i + 1) % len;
  }
  return out;
}

/**
 * node:test 스위트용 내부 export. 공개 API의 일부가 아님 — 컨슈머는 이
 * 객체에서 import해서는 안 된다. 테스트 파일이 정수 강제 변환 엣지 케이스를
 * 고정할 수 있도록 노출하되, `safeNonNegativeInt`를 모듈의 범용 표면으로
 * 새지 않게 하기 위함이다.
 */
export const __test__ = {
  safeNonNegativeInt,
};
