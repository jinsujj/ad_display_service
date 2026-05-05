"use client";

/**
 * 광고 id 조회 폼 — 입력한 UUID 의 스케줄 에디터(/ads/{id})로 라우팅.
 *
 * 의도적으로 최소: 텍스트 입력 1개, 동기 trim + UUID 형태 검증, 제출 시
 * `router.push("/ads/{trimmed-id}")`.
 */

import { useState } from "react";
import { useRouter } from "next/navigation";

import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";

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
    <form
      className="max-w-narrow space-y-3"
      onSubmit={handleSubmit}
      noValidate
    >
      <div className="space-y-1.5">
        <Label htmlFor="ad-lookup-id">광고 ID (UUID)</Label>
        <Input
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
          aria-invalid={Boolean(error) || undefined}
          aria-describedby={error ? "ad-lookup-id-err" : undefined}
          className="font-mono"
        />
        {error && (
          <p
            id="ad-lookup-id-err"
            className="text-xs text-destructive"
            role="alert"
          >
            {error}
          </p>
        )}
      </div>
      <div className="flex">
        <Button type="submit" className="w-full sm:w-auto">
          스케줄 에디터 열기
        </Button>
      </div>
    </form>
  );
}

export default AdLookupForm;
