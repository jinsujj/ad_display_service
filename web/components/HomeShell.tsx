"use client";

/**
 * 홈 진입점의 클라이언트 분기 — localStorage 의 토큰 유무에 따라:
 *   - 미인증: <Landing/> (마케팅 랜딩)
 *   - 인증:   <ConsoleHome/> (광고주 콘솔 대시 카드)
 *
 * SSR 단계에서는 토큰을 알 수 없으므로 hydration 후 첫 effect 에서 결정한다.
 * mismatch 회피를 위해 첫 페인트는 비워 두고 클라이언트가 결정 후 렌더.
 */

import { useEffect, useState } from "react";
import { readStoredAuthToken } from "@/lib/api";
import { Landing } from "./Landing";
import { ConsoleHome } from "./ConsoleHome";

export function HomeShell() {
  const [authed, setAuthed] = useState<boolean | null>(null);

  useEffect(() => {
    setAuthed(!!readStoredAuthToken());
    const onChange = () => setAuthed(!!readStoredAuthToken());
    window.addEventListener("storage", onChange);
    window.addEventListener("adsignage:auth-changed", onChange);
    return () => {
      window.removeEventListener("storage", onChange);
      window.removeEventListener("adsignage:auth-changed", onChange);
    };
  }, []);

  if (authed === null) {
    // 첫 페인트 — 깜빡임 줄이기 위해 비움
    return <div aria-hidden="true" />;
  }
  return authed ? <ConsoleHome /> : <Landing />;
}

export default HomeShell;
