"use client";

/**
 * 모바일 햄버거 메뉴 — 좌측 Sheet 드로어.
 *
 * 데스크탑은 헤더에 가로 nav 가 보이고, md 미만에서만 이 트리거가 노출된다
 * (AppChrome 에서 `md:hidden` 으로 감쌈). 활성 라우트는 amber 액센트로
 * 표시되고, 링크 탭하면 SheetClose 가 자동 닫힘.
 */

import { Menu } from "lucide-react";
import { usePathname } from "next/navigation";
import {
  Sheet,
  SheetClose,
  SheetContent,
  SheetTrigger,
} from "@/components/ui/sheet";
import { Button } from "@/components/ui/button";
import { AuthHeader } from "./AuthHeader";
import { cn } from "@/lib/utils";

interface NavLink {
  href: string;
  label: string;
}

interface Props {
  links: NavLink[];
}

export function MobileMenu({ links }: Props) {
  const pathname = usePathname() ?? "";

  return (
    <Sheet>
      <SheetTrigger asChild>
        <Button
          variant="outline"
          size="icon"
          aria-label="메뉴 열기"
          className="md:hidden"
        >
          <Menu className="h-5 w-5" />
        </Button>
      </SheetTrigger>
      <SheetContent side="left" className="flex flex-col gap-6 px-5 pt-12">
        <a
          href="/"
          className="text-base font-semibold tracking-tight text-foreground"
        >
          AdSignage 어드민
        </a>
        <nav className="flex flex-col" aria-label="모바일 내비게이션">
          {links.map((link) => {
            const active =
              link.href === "/"
                ? pathname === "/"
                : pathname.startsWith(link.href);
            return (
              <SheetClose asChild key={link.href}>
                <a
                  href={link.href}
                  aria-current={active ? "page" : undefined}
                  className={cn(
                    "flex h-12 items-center rounded-md px-3 text-base font-medium transition-colors",
                    active
                      ? "bg-accent/15 text-accent"
                      : "text-foreground/85 hover:bg-accent/10 hover:text-foreground"
                  )}
                >
                  {link.label}
                </a>
              </SheetClose>
            );
          })}
        </nav>
        <div className="mt-auto border-t border-border pt-4">
          <AuthHeader variant="compact" />
        </div>
      </SheetContent>
    </Sheet>
  );
}

export default MobileMenu;
