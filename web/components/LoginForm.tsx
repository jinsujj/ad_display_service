"use client";

/**
 * 광고주 로그인 폼.
 *
 * - POST /api/auth/login 호출
 * - 성공 시 토큰을 localStorage에 저장 (lib/auth.ts 의 [login] 가 처리)
 * - 성공 후에는 쿼리 `?next=/path` 로 받은 페이지로 또는 기본 `/` 로 이동
 *
 * 백엔드의 fieldErrors 는 form-level 메시지로 노출 (이메일/비번 두 필드만
 * 있으니 별도 inline 분리는 과함).
 */

import { useCallback, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";

import { describeAuthError, login } from "@/lib/auth";

type State =
  | { kind: "idle" }
  | { kind: "submitting" }
  | { kind: "error"; message: string };

export function LoginForm() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const next = searchParams.get("next") || "/";

  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [state, setState] = useState<State>({ kind: "idle" });

  const handleSubmit = useCallback(
    async (e: React.FormEvent<HTMLFormElement>) => {
      e.preventDefault();
      if (state.kind === "submitting") return;
      const trimmedEmail = email.trim();
      if (!trimmedEmail || !password) {
        setState({ kind: "error", message: "이메일과 비밀번호를 모두 입력해 주세요." });
        return;
      }
      setState({ kind: "submitting" });
      try {
        await login({ email: trimmedEmail, password });
        // 인증 후 보호 페이지로 이동.
        router.replace(next);
      } catch (err) {
        const desc = describeAuthError(err);
        setState({
          kind: "error",
          message:
            desc.status === 401
              ? "이메일 또는 비밀번호가 올바르지 않습니다."
              : desc.message,
        });
      }
    },
    [email, password, next, state.kind, router],
  );

  const submitting = state.kind === "submitting";

  return (
    <form className="auth-form" onSubmit={handleSubmit} noValidate>
      <fieldset className="auth-form__fieldset" disabled={submitting}>
        <legend className="auth-form__legend">광고주 로그인</legend>

        <label htmlFor="login-email" className="auth-form__label">
          이메일
        </label>
        <input
          id="login-email"
          name="email"
          type="email"
          autoComplete="email"
          required
          value={email}
          onChange={(e) => setEmail(e.target.value)}
          className="auth-form__input"
        />

        <label htmlFor="login-password" className="auth-form__label">
          비밀번호
        </label>
        <input
          id="login-password"
          name="password"
          type="password"
          autoComplete="current-password"
          required
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          className="auth-form__input"
        />

        {state.kind === "error" && (
          <div className="notice notice-error" role="alert">
            {state.message}
          </div>
        )}

        <div className="toolbar auth-form__actions">
          <button type="submit" className="btn" aria-busy={submitting}>
            {submitting ? "로그인 중…" : "로그인"}
          </button>
        </div>
      </fieldset>
    </form>
  );
}

export default LoginForm;
