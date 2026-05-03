"use client";

/**
 * 보호 라우트 게이트.
 *
 * 사용 패턴 — 서버 컴포넌트 페이지의 최상단을 감싼다:
 *
 * ```tsx
 * import { AuthGuard } from "@/components/AuthGuard";
 *
 * export default function Page() {
 *   return (
 *     <AuthGuard>
 *       <SectionWithProtectedContent />
 *     </AuthGuard>
 *   );
 * }
 * ```
 *
 * 동작:
 *   - 마운트 시 localStorage 의 JWT(`adsignage_auth_token`)를 확인.
 *   - 토큰이 없으면 즉시 `/login?next=<현재 경로>` 로 client-side redirect.
 *     이 redirect는 검사 직후 한 프레임 내에 일어나므로 보호 콘텐츠는
 *     사실상 화면에 노출되지 않는다.
 *   - 토큰이 있으면 children을 그대로 렌더.
 *
 * 한계 — 솔직히 말씀드림:
 *   1. SSR 단계에서는 localStorage 가 없어서 검사할 수 없으니 서버 컴포넌트가
 *      미인증 상태로 한 번 fetch를 시도할 수 있다. 백엔드는 그 호출을 401로
 *      거절하고, 페이지의 try/catch가 빈 데이터로 처리. 즉 짧은 깜빡임은
 *      가능하지만 데이터는 절대 새지 않는다.
 *   2. 진짜 SSR 가드는 토큰을 쿠키로 옮겨야 가능 (이번 데모 범위 밖).
 *
 * 토큰 모양만 확인하고 *유효성*은 백엔드가 401로 판단하는 책임을 진다.
 * 만료된 토큰을 들고 있으면 보호 페이지가 보이긴 하지만 모든 fetch가 401이
 * 나며, 이때는 소비자(예: VideoUploadForm) 가 "재로그인 필요" 메시지를 띄움.
 */

import { useEffect, useState } from "react";
import { AUTH_TOKEN_STORAGE_KEY } from "@/lib/api";

interface AuthGuardProps {
  children: React.ReactNode;
  /** 미인증 시 보낼 곳. 기본: `/login` (현재 경로가 자동으로 next 쿼리로 첨부) */
  redirectTo?: string;
}

export function AuthGuard({ children, redirectTo = "/login" }: AuthGuardProps) {
  const [ok, setOk] = useState<boolean>(false);

  useEffect(() => {
    let token: string | null = null;
    try {
      token = window.localStorage.getItem(AUTH_TOKEN_STORAGE_KEY);
    } catch {
      token = null;
    }
    if (!token) {
      const here = window.location.pathname + window.location.search;
      const next = encodeURIComponent(here);
      // replace를 써서 뒤로가기 누르면 보호 페이지가 다시 등장하지 않도록.
      window.location.replace(`${redirectTo}?next=${next}`);
      return;
    }
    setOk(true);
  }, [redirectTo]);

  if (!ok) {
    // redirect 전 잠깐 보일 placeholder. SSR/hydration mismatch 회피용으로
    // 항상 같은 마크업.
    return (
      <div className="muted" role="status" aria-live="polite">
        인증 확인 중…
      </div>
    );
  }
  return <>{children}</>;
}

export default AuthGuard;
