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
 *
 * variant="compact" 는 모바일 Sheet 메뉴 안에서 쓰는 큼지막한 세로 배치;
 * 기본값은 데스크탑 헤더 우측의 한 줄 인라인.
 */

import { useCallback, useEffect, useState } from "react";
import { logout, readStoredAuthUser, type StoredAuthUser } from "@/lib/auth";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";

interface AuthHeaderProps {
  variant?: "inline" | "compact";
  className?: string;
}

export function AuthHeader({ variant = "inline", className }: AuthHeaderProps) {
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

  const onLogout = () => {
    logout();
    window.location.href = "/";
  };

  // SSR/hydration 중에는 사용자 영역을 비워 두어 hydration mismatch 회피.
  if (!hydrated) {
    return <div className={cn("min-h-9", className)} aria-hidden="true" />;
  }

  if (variant === "compact") {
    if (!user) {
      return (
        <div className={cn("flex flex-col gap-2", className)}>
          <Button asChild variant="outline" className="justify-center">
            <a href="/login">로그인</a>
          </Button>
          <Button asChild className="justify-center">
            <a href="/signup">회원가입</a>
          </Button>
        </div>
      );
    }
    return (
      <div className={cn("flex flex-col gap-2", className)}>
        <div className="flex items-center justify-between gap-2 rounded-md border border-border bg-card px-3 py-2 text-sm">
          <span
            className="truncate font-medium text-foreground"
            title={user.advertiserId}
          >
            {user.email}
          </span>
          {user.role === "OPERATOR" && (
            <Badge variant="ok" title="플랫폼 운영자 — 디바이스/큐 관리 권한">
              OPERATOR
            </Badge>
          )}
        </div>
        <Button variant="outline" onClick={onLogout} className="justify-center">
          로그아웃
        </Button>
      </div>
    );
  }

  if (!user) {
    return (
      <div className={cn("flex items-center gap-2 text-sm", className)}>
        <a
          href="/login"
          className="rounded-md px-2 py-1 text-foreground/85 hover:text-foreground hover:bg-accent/15"
        >
          로그인
        </a>
        <span className="text-muted-foreground">·</span>
        <a
          href="/signup"
          className="rounded-md px-2 py-1 text-foreground/85 hover:text-foreground hover:bg-accent/15"
        >
          회원가입
        </a>
      </div>
    );
  }

  return (
    <div className={cn("flex items-center gap-2 text-sm", className)}>
      <span
        className="max-w-[180px] truncate text-foreground/85"
        title={user.advertiserId}
      >
        {user.email}
      </span>
      {user.role === "OPERATOR" && (
        <Badge variant="ok" title="플랫폼 운영자 — 디바이스/큐 관리 권한">
          OPERATOR
        </Badge>
      )}
      <Button
        type="button"
        variant="outline"
        size="sm"
        onClick={onLogout}
      >
        로그아웃
      </Button>
    </div>
  );
}

export default AuthHeader;
