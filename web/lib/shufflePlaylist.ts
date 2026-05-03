/**
 * 디바이스 큐에 담긴 광고들을 라운드 로빈할 때 *순서가 잘 섞여 보이도록*
 * 보조하는 셔플 유틸.
 *
 * 운영자 경험 의도:
 *   디바이스에 여러 광고를 담아 두면 "A 가 한참 나오고 B 가 나오고 C 가 나오는"
 *   식이 아니라 "A B C → C A B → B C A …" 처럼 매 사이클마다 순서가 바뀌어야
 *   "쌓아둔 광고가 잘 섞여서 보인다" 는 느낌이 난다. 백엔드는 큐를 단순히
 *   addedAt 내림차순으로 내려주므로(결정적), 셔플은 클라이언트에서 사이클
 *   경계마다 적용한다.
 *
 * # 셔플의 책임 / 비책임
 *
 *   - **책임**: ads 의 표시 순서를 섞는다.
 *   - **비책임**:
 *       - 광고 빈도 가중치(dailyCount 비례 분배 등) — 그건 캡 필터
 *         (`filterUnderCap`) + 라운드 로빈이 자연스럽게 맞춘다.
 *       - 시간 윈도우 / 캠페인 기간 필터 — 상위에서 이미 처리됨.
 *       - 라운드 로빈 인덱스 자체 — 그대로 사용. 셔플은 "어떤 배열을
 *         라운드 로빈할지" 만 바꾼다.
 *
 * # 셔플 시점
 *
 *   1. `ads` 의 *식별 집합* 이 바뀔 때(추가/제거/만료) — 새 진입 광고가 잘
 *      보이도록 즉시 새 순열로 재시작.
 *   2. 한 사이클(N 회 재생)이 끝나서 인덱스가 0 으로 wrap 될 때 — 같은
 *      세트 안에서도 매 사이클 새로운 순서를 보여 "지루함" 을 줄인다.
 *
 * # 결정적 vs 무작위
 *
 * `Math.random()` 만 쓰면 현재 사이클이 끝났을 때만 차이가 나고 단위 테스트가
 * 어렵다. 본 헬퍼는 [seed] 를 받아 결정적 PRNG(mulberry32) 로 셔플한다 —
 * 호출자가 매 사이클 다른 seed(예: `Date.now()` 또는 단순 카운터) 를 주면
 * 무작위처럼 보이고, 테스트는 고정 seed 로 동일 결과를 단언할 수 있다.
 */

/**
 * 32-bit seed 를 받아 [0, 1) 사이의 균등 분포 의사 난수를 만드는 generator.
 * Fisher-Yates 셔플의 결정성을 위해 자체 PRNG 를 직접 두며, `Math.random` 의
 * 엔진별 차이로부터 격리한다.
 */
function mulberry32(seed: number): () => number {
  let s = seed >>> 0;
  return function next(): number {
    s = (s + 0x6d2b79f5) >>> 0;
    let t = s;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

/**
 * [items] 의 새 셔플된 복사본을 반환. 입력은 mutate 하지 않음.
 * `length <= 1` 이면 그대로 반환(셔플 의미 없음).
 *
 * @param items 셔플 대상 배열
 * @param seed  결정적 PRNG seed. 같은 seed + 같은 입력 → 같은 결과.
 */
export function shuffleWithSeed<T>(items: readonly T[], seed: number): T[] {
  const out = items.slice();
  if (out.length <= 1) return out;
  const rand = mulberry32(seed);
  for (let i = out.length - 1; i > 0; i -= 1) {
    const j = Math.floor(rand() * (i + 1));
    const tmp = out[i];
    out[i] = out[j];
    out[j] = tmp;
  }
  return out;
}

/**
 * 광고 배열의 *식별 키* 를 만든다. 정렬한 adId 들의 join — 순서 변경에는
 * 영향받지 않으므로 "같은 집합인지" 비교에 적합. `useMemo` 의 deps 로 쓰면
 * 셔플이 단순 순서 변경으로 재실행되지 않는다.
 */
export function adIdSetKey(items: readonly { adId: string }[]): string {
  if (items.length === 0) return "";
  const ids = items.map((a) => a.adId);
  ids.sort();
  return ids.join("|");
}
