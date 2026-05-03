"use client";

/**
 * 광고주 회원가입 폼.
 *
 * 흐름:
 *   1. POST /api/auth/signup 으로 광고주 생성 (백엔드는 토큰을 발급하지 않음).
 *   2. 그 직후 자동으로 [login] 을 호출해 JWT 발급 + localStorage 저장.
 *   3. `?next=/path` 또는 `/` 로 이동.
 *
 * 백엔드 검증:
 *   - 이메일: @Email + 최대 255자
 *   - 비밀번호: 8~100자
 *   - 중복 이메일은 409 Conflict.
 */

import { useCallback, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";

import { describeAuthError, login, signup } from "@/lib/auth";

type State =
  | { kind: "idle" }
  | { kind: "submitting" }
  | { kind: "error"; message: string; fieldErrors?: Record<string, string> };

export function SignupForm() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const next = searchParams.get("next") || "/";

  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [passwordConfirm, setPasswordConfirm] = useState("");
  const [state, setState] = useState<State>({ kind: "idle" });

  const handleSubmit = useCallback(
    async (e: React.FormEvent<HTMLFormElement>) => {
      e.preventDefault();
      if (state.kind === "submitting") return;

      const trimmedEmail = email.trim();
      if (!trimmedEmail) {
        setState({ kind: "error", message: "이메일을 입력해 주세요." });
        return;
      }
      if (password.length < 8) {
        setState({
          kind: "error",
          message: "비밀번호는 8자 이상이어야 합니다.",
          fieldErrors: { password: "8~100자" },
        });
        return;
      }
      if (password !== passwordConfirm) {
        setState({
          kind: "error",
          message: "비밀번호 확인이 일치하지 않습니다.",
          fieldErrors: { passwordConfirm: "비밀번호와 동일하게 입력" },
        });
        return;
      }

      setState({ kind: "submitting" });
      try {
        await signup({ email: trimmedEmail, password });
        // 가입은 토큰을 주지 않으므로 곧바로 로그인 호출.
        await login({ email: trimmedEmail, password });
        router.replace(next);
      } catch (err) {
        const desc = describeAuthError(err);
        setState({
          kind: "error",
          message:
            desc.status === 409
              ? "이미 가입된 이메일입니다. 로그인 페이지를 이용해 주세요."
              : desc.message,
          fieldErrors: desc.fieldErrors,
        });
      }
    },
    [email, password, passwordConfirm, next, state.kind, router],
  );

  const submitting = state.kind === "submitting";
  const fieldErrors =
    state.kind === "error" ? state.fieldErrors ?? {} : {};

  return (
    <form className="auth-form" onSubmit={handleSubmit} noValidate>
      <fieldset className="auth-form__fieldset" disabled={submitting}>
        <legend className="auth-form__legend">광고주 회원가입</legend>

        <label htmlFor="signup-email" className="auth-form__label">
          이메일
        </label>
        <input
          id="signup-email"
          name="email"
          type="email"
          autoComplete="email"
          required
          value={email}
          onChange={(e) => setEmail(e.target.value)}
          className="auth-form__input"
          aria-invalid={Boolean(fieldErrors.email) || undefined}
        />
        {fieldErrors.email && (
          <div className="schedule-form__field-error" role="alert">
            {fieldErrors.email}
          </div>
        )}

        <label htmlFor="signup-password" className="auth-form__label">
          비밀번호
          <span className="muted"> · 8~100자</span>
        </label>
        <input
          id="signup-password"
          name="password"
          type="password"
          autoComplete="new-password"
          minLength={8}
          maxLength={100}
          required
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          className="auth-form__input"
          aria-invalid={Boolean(fieldErrors.password) || undefined}
        />
        {fieldErrors.password && (
          <div className="schedule-form__field-error" role="alert">
            {fieldErrors.password}
          </div>
        )}

        <label htmlFor="signup-password-confirm" className="auth-form__label">
          비밀번호 확인
        </label>
        <input
          id="signup-password-confirm"
          name="passwordConfirm"
          type="password"
          autoComplete="new-password"
          required
          value={passwordConfirm}
          onChange={(e) => setPasswordConfirm(e.target.value)}
          className="auth-form__input"
          aria-invalid={Boolean(fieldErrors.passwordConfirm) || undefined}
        />
        {fieldErrors.passwordConfirm && (
          <div className="schedule-form__field-error" role="alert">
            {fieldErrors.passwordConfirm}
          </div>
        )}

        {state.kind === "error" && (
          <div className="notice notice-error" role="alert">
            {state.message}
          </div>
        )}

        <div className="toolbar auth-form__actions">
          <button type="submit" className="btn" aria-busy={submitting}>
            {submitting ? "가입 중…" : "가입하고 로그인"}
          </button>
        </div>
      </fieldset>
    </form>
  );
}

export default SignupForm;
