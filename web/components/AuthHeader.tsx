"use client";

/**
 * 어드민 웹 공통 헤더의 "사용자 영역" 부분.
 *
 * 서버 컴포넌트인 layout.tsx 에서는 localStorage 를 읽을 수 없으므로,
 * 헤더의 인증 상태 표시(로그인 사용자 이메일 / 로그아웃 버튼 또는 로그인/
 * 가입 링크)를 이 클라이언트 컴포넌트로 분리한다.
 *
 * 동작:
 *  - 마운트 시 localStorage 에서 사용자 정보를 읽어 1차 렌더.
 *  - 다른 탭에서 로그인/로그아웃이 일어나면 native `storage` 이벤트로 갱신.
 *  - 같은 탭 안에서 [login] / [logout] 호출 시에는 커스텀
 *    `adsignage:auth-changed` 이벤트로 갱신.
 */

import { useCallback, useEffect, useState } from "react";
import { logout, readStoredAuthUser, type StoredAuthUser } from "@/lib/auth";

export function AuthHeader() {
  const [user, setUser] = useState<StoredAuthUser | null>(null);
  const [hydrated, setHydrated] = useState(false);

  const refresh = useCallback(() => {
    setUser(readStoredAuthUser());
    setHydrated(true);
  }, []);

  useEffect(() => {
    refresh();
    const onStorage = () => refresh();
    window.addEventListener("storage", onStorage);
    window.addEventListener("adsignage:auth-changed", onStorage);
    return () => {
      window.removeEventListener("storage", onStorage);
      window.removeEventListener("adsignage:auth-changed", onStorage);
    };
  }, [refresh]);

  // SSR/hydration 중에는 사용자 영역을 비워 두어 hydration mismatch 회피.
  if (!hydrated) {
    return <div className="auth-header" aria-hidden="true" />;
  }

  if (!user) {
    return (
      <div className="auth-header">
        <a href="/login" className="auth-link">로그인</a>
        <span className="auth-sep">·</span>
        <a href="/signup" className="auth-link">회원가입</a>
      </div>
    );
  }

  return (
    <div className="auth-header">
      <span className="auth-user" title={user.advertiserId}>
        {user.email}
      </span>
      {user.role === "OPERATOR" && (
        <span
          className="pill pill-ok"
          style={{ marginLeft: 6, fontSize: 10 }}
          title="플랫폼 운영자 — 디바이스/큐 관리 권한"
        >
          OPERATOR
        </span>
      )}
      <button
        type="button"
        className="auth-logout"
        onClick={() => {
          logout();
          // 보호 페이지에 머물러 있는 경우를 대비해 랜딩으로 보낸다.
          window.location.href = "/";
        }}
      >
        로그아웃
      </button>
    </div>
  );
}

export default AuthHeader;
