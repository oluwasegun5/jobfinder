"use client";

import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { useCallback, useState, type FormEvent } from "react";

import { Button } from "@/components/ui/button";
import { api } from "@/lib/api";

import { problemCode, problemMessage } from "./api-errors";
import { useAuth } from "./auth-provider";
import { Field, FormError, FormNotice } from "./form-parts";
import { GoogleButton } from "./google-button";
import { safeNextPath } from "./safe-next";

export function LoginForm() {
  const router = useRouter();
  const next = safeNextPath(useSearchParams().get("next"));
  const { signIn } = useAuth();
  const [pending, setPending] = useState(false);
  const [error, setError] = useState("");
  const [unverifiedEmail, setUnverifiedEmail] = useState<string | null>(null);
  const [resent, setResent] = useState(false);

  const goToApp = useCallback(() => router.replace(next), [router, next]);

  async function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    const email = String(form.get("email")).trim();
    setPending(true);
    setError("");
    setUnverifiedEmail(null);
    setResent(false);
    const { data, error: problem } = await api.POST("/auth/login", {
      body: { email, password: String(form.get("password")) },
    });
    if (data) {
      await signIn(data);
      goToApp();
      return;
    }
    if (problemCode(problem) === "email_not_verified") setUnverifiedEmail(email);
    else setError(problemMessage(problem, "Could not sign in. Please try again."));
    setPending(false);
  }

  async function resend() {
    if (!unverifiedEmail) return;
    await api.POST("/auth/resend-verification", { body: { email: unverifiedEmail } });
    setResent(true);
  }

  return (
    <div className="flex flex-col gap-4">
      <h1 className="text-xl font-semibold tracking-tight">Sign in</h1>
      <form onSubmit={onSubmit} className="flex flex-col gap-4">
        <Field id="email" label="Email" type="email" autoComplete="email" required maxLength={254} />
        <Field
          id="password"
          label="Password"
          type="password"
          autoComplete="current-password"
          required
          maxLength={72}
        />
        <FormError>{error}</FormError>
        {unverifiedEmail && (
          <FormNotice>
            Please verify your email first. We sent a link to {unverifiedEmail}.{" "}
            {resent ? (
              "A new link is on its way."
            ) : (
              <button type="button" onClick={resend} className="underline underline-offset-4">
                Resend the link
              </button>
            )}
          </FormNotice>
        )}
        <Button type="submit" size="lg" disabled={pending}>
          {pending ? "Signing in…" : "Sign in"}
        </Button>
      </form>
      <GoogleButton onSignedIn={goToApp} onError={setError} />
      <div className="flex justify-between text-sm">
        <Link href="/forgot-password" className="underline underline-offset-4">
          Forgot password?
        </Link>
        <Link href="/signup" className="underline underline-offset-4">
          Create an account
        </Link>
      </div>
    </div>
  );
}
