"use client";

/**
 * 어드민 셸(헤더 + main 컨테이너) — /player/ 라우트에서는 숨긴다.
 *
 * /player/{deviceId} 는 안드로이드 광고판 WebView 가 로드하는 페이지로,
 * 어드민 nav 가 보이면 광고 화면이 가려진다. usePathname 으로 클라이언트
 * 측에서 분기 — 광고판은 픽셀 한 줄도 어드민 UI 가 노출되지 않아야 한다.
 */

import { useEffect, useState, type ReactNode } from "react";
import { usePathname } from "next/navigation";
import { AuthHeader } from "./AuthHeader";
import { MobileMenu } from "./MobileMenu";
import { readStoredAuthUser, type StoredAuthUser } from "@/lib/auth";
import { cn } from "@/lib/utils";

interface Props {
  children: ReactNode;
}

export function AppChrome({ children }: Props) {
  const pathname = usePathname() ?? "";
  const isPlayer = pathname.startsWith("/player");

  // role 에 따라 메뉴 분기. 클라이언트 storage 라 SSR 첫 렌더에선 null →
  // 양쪽 메뉴 다 숨김 보다는 "광고/영상" 만 노출하고 OPERATOR 면 hydration
  // 후 "디바이스" 가 추가로 나타나는 흐름이 깔끔.
  const [user, setUser] = useState<StoredAuthUser | null>(null);
  useEffect(() => {
    setUser(readStoredAuthUser());
    const onAuth = () => setUser(readStoredAuthUser());
    window.addEventListener("adsignage:auth-changed", onAuth);
    window.addEventListener("storage", onAuth);
    return () => {
      window.removeEventListener("adsignage:auth-changed", onAuth);
      window.removeEventListener("storage", onAuth);
    };
  }, []);

  if (isPlayer) {
    // 광고판: chrome 없이 children 만 렌더 — PlayerClient 가 자체 풀스크린.
    return <>{children}</>;
  }

  const isOperator = user?.role === "OPERATOR";
  const navLinks = [
    { href: "/videos", label: "영상" },
    { href: "/ads", label: "광고" },
    // 디바이스 탭은 OPERATOR 만 — 광고주는 자기 광고가 어디 송출 중
    // 인지 광고 상세에서 read-only 로 본다.
    ...(isOperator ? [{ href: "/devices", label: "디바이스" }] : []),
  ];

  return (
    // 라디얼 그라디언트 배경 — 광고판 글로우 인상. body 가 아니라 이 셸에
    // 한정해 player 풀스크린 라우트엔 새지 않게 한다.
    <div className="flex min-h-dvh flex-col bg-[radial-gradient(1100px_500px_at_12%_-10%,rgba(245,176,66,0.08),transparent_65%),radial-gradient(900px_400px_at_95%_-10%,rgba(99,102,241,0.06),transparent_65%)]">
      <header className="sticky top-0 z-40 border-b border-border bg-background/85 backdrop-blur-md">
        <div className="mx-auto flex max-w-content items-center gap-3 px-4 py-3 md:px-6 md:py-3">
          <a
            href="/"
            className="text-sm font-semibold tracking-tight text-foreground md:text-base"
          >
            AdSignage&nbsp;어드민
          </a>
          <nav
            className="hidden items-center gap-1 md:ml-4 md:flex"
            aria-label="주요 내비게이션"
          >
            {navLinks.map((link) => {
              const active =
                link.href === "/"
                  ? pathname === "/"
                  : pathname.startsWith(link.href);
              return (
                <a
                  key={link.href}
                  href={link.href}
                  aria-current={active ? "page" : undefined}
                  className={cn(
                    "rounded-md px-3 py-1.5 text-sm transition-colors",
                    active
                      ? "bg-accent/15 text-accent"
                      : "text-foreground/85 hover:bg-accent/10 hover:text-foreground"
                  )}
                >
                  {link.label}
                </a>
              );
            })}
          </nav>
          <div className="ml-auto flex items-center gap-2">
            <div className="hidden md:block">
              <AuthHeader />
            </div>
            <MobileMenu links={navLinks} />
          </div>
        </div>
      </header>
      <main className="mx-auto max-w-content px-4 py-6 md:px-6 md:py-8">
        {children}
      </main>
    </div>
  );
}

export default AppChrome;
