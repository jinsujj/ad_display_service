"use client";

/**
 * 광고주 프로필 페이지.
 *
 * localStorage 의 토큰/유저 정보를 읽어 read-only 로 표시. 비밀번호 변경
 * 같은 mutation 은 백엔드 엔드포인트가 추가되어야 하므로 "준비 중" 표기.
 */

import { useEffect, useState } from "react";

import { logout, readStoredAuthUser, type StoredAuthUser } from "@/lib/auth";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";

export function ProfileClient() {
  const [user, setUser] = useState<StoredAuthUser | null>(null);
  const [hydrated, setHydrated] = useState(false);

  useEffect(() => {
    setUser(readStoredAuthUser());
    setHydrated(true);
  }, []);

  if (!hydrated) {
    return (
      <div className="text-sm text-muted-foreground">불러오는 중…</div>
    );
  }

  if (!user) {
    return (
      <Alert variant="destructive">
        <AlertDescription>
          로그인 정보가 없습니다. 다시 로그인해 주세요.
        </AlertDescription>
      </Alert>
    );
  }

  const onLogout = () => {
    logout();
    window.location.href = "/";
  };

  const roleLabel =
    user.role === "OPERATOR"
      ? "플랫폼 운영자"
      : user.role === "ADVERTISER"
        ? "광고주"
        : "—";

  return (
    <div className="space-y-6">
      <Card>
        <CardHeader>
          <CardTitle>계정 정보</CardTitle>
        </CardHeader>
        <CardContent>
          <dl className="grid grid-cols-[120px_1fr] gap-y-3 text-sm">
            <dt className="text-muted-foreground">이메일</dt>
            <dd className="font-medium">{user.email}</dd>

            <dt className="text-muted-foreground">권한</dt>
            <dd>
              {user.role === "OPERATOR" ? (
                <Badge variant="ok">{roleLabel}</Badge>
              ) : (
                <Badge variant="muted">{roleLabel}</Badge>
              )}
            </dd>
          </dl>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>비밀번호 변경</CardTitle>
        </CardHeader>
        <CardContent>
          <p className="text-sm text-muted-foreground">
            비밀번호 변경 기능은 곧 제공될 예정입니다. 지금은 로그아웃 후
            재가입 또는 운영자에게 문의해 주세요.
          </p>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>이메일 알림</CardTitle>
        </CardHeader>
        <CardContent>
          <p className="text-sm text-muted-foreground">
            캠페인 종료 알림·송출 리포트 메일 옵션은 추후 추가됩니다.
          </p>
        </CardContent>
      </Card>

      <div className="flex flex-col gap-2 sm:flex-row sm:justify-end">
        <Button
          variant="outline"
          onClick={onLogout}
          className="w-full sm:w-auto"
        >
          로그아웃
        </Button>
      </div>
    </div>
  );
}

export default ProfileClient;
