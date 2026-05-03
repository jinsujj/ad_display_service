"use client";

/**
 * 광고 id 조회 폼 (AC 3, Sub-AC 3 — [AdScheduleForm]의 동반자).
 *
 * 백엔드가 `GET /api/ads` 목록을 노출할 때까지, 이것은 운영자를 `/ads`
 * 인덱스에서 `/ads/{id}`의 광고별 스케줄 에디터로 데려가는 진입점이다.
 * 의도적으로 최소한:
 *
 *   - 광고 UUID를 위한 텍스트 입력 1개,
 *   - 동기 trim + 비-empty + UUID 형태 검증 — 명백히 잘못된 id가 다음
 *     페이지에서 404가 나지 않도록,
 *   - 제출 시 `router.push("/ads/{trimmed-id}")` — 대상 페이지가 실제 폼을
 *     호스팅.
 *
 * 왜 클라이언트 컴포넌트인가:
 *   검증 후 내비게이션을 위해 `useRouter`가 필요하고, trim/validate UX를
 *   위해 컨트롤드 입력이 필요하다. 래퍼 페이지(`app/ads/page.tsx`)는 서버
 *   컴포넌트로 유지된다.
 */

import { useState } from "react";
import { useRouter } from "next/navigation";

/**
 * 느슨한 UUID 형태 가드. 서버도 광고 id로 36자 문자열 무엇이든 받아들이므로
 * 엄격한 v4를 요구하지 않는다(UUID는 서버에서 `UUID.randomUUID()`로
 * 생성되지만 컬럼은 단순 `varchar(36)`). 이 검사는 명백한 붙여넣기 실수를
 * 잡기 위한 순수 UX 레이어.
 */
const ADID_REGEX = /^[0-9a-fA-F-]{8,40}$/;

export function AdLookupForm() {
  const router = useRouter();
  const [adId, setAdId] = useState<string>("");
  const [error, setError] = useState<string | null>(null);

  function handleSubmit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const trimmed = adId.trim();
    if (!trimmed) {
      setError("광고 ID를 입력해 주세요.");
      return;
    }
    if (!ADID_REGEX.test(trimmed)) {
      setError(
        "UUID 형식이 아닙니다. 광고 생성 응답에서 받은 ID를 붙여 넣으세요.",
      );
      return;
    }
    setError(null);
    router.push(`/ads/${encodeURIComponent(trimmed)}`);
  }

  return (
    <form className="ad-lookup-form" onSubmit={handleSubmit} noValidate>
      <label htmlFor="ad-lookup-id" className="schedule-form__label">
        광고 ID (UUID)
      </label>
      <input
        id="ad-lookup-id"
        name="adId"
        type="text"
        value={adId}
        onChange={(e) => {
          setAdId(e.target.value);
          if (error) setError(null);
        }}
        placeholder="e.g. 7c4f3e92-4d8e-4d18-bc0e-3b2f1a0e5e21"
        autoComplete="off"
        spellCheck={false}
        className="schedule-form__input"
        aria-invalid={Boolean(error) || undefined}
        aria-describedby={error ? "ad-lookup-id-err" : undefined}
      />
      {error && (
        <div id="ad-lookup-id-err" className="schedule-form__field-error" role="alert">
          {error}
        </div>
      )}
      <div className="toolbar" style={{ marginTop: 12 }}>
        <button type="submit" className="btn">
          스케줄 에디터 열기
        </button>
      </div>
    </form>
  );
}

export default AdLookupForm;
