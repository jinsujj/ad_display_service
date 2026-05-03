/**
 * UUID / 시간 같은 길거나 정형화된 값을 어드민 UI 용으로 짧게 보여주는
 * 공통 포맷터.
 *
 * 운영자는 디버그 시점에만 풀 UUID 가 필요하고 평소엔 식별 가능한 짧은
 * prefix 면 충분하다. UUID 가 그대로 노출되면 모바일에서 줄바꿈/오버플로우
 * 가 생기고 시각적 노이즈가 크다.
 */

/**
 * UUID 같이 긴 식별자를 `aaaaaaaa…dddd` 형태로 짧게.
 * 12자 이하면 그대로 반환.
 */
export function shortId(id: string | null | undefined): string {
  if (!id) return "—";
  if (id.length <= 12) return id;
  return `${id.slice(0, 8)}…${id.slice(-4)}`;
}

/**
 * 짧은 ID 를 `<code>` 안에 렌더링할 때 함께 쓸 className.
 * `title` 속성으로 풀 ID 를 hover 시 노출하기를 권장.
 */
export const SHORT_ID_TITLE_HINT = "마우스를 올리면 전체 ID 가 보입니다";
