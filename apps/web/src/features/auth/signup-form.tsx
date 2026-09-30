"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useCallback, useState, type FormEvent } from "react";

import { Button } from "@/components/ui/button";
import { api } from "@/lib/api";

import { problemMessage } from "./api-errors";
import { Field, FormError, FormNotice } from "./form-parts";
import { GoogleButton } from "./google-button";

export function SignupForm() {
  const router = useRouter();
  const [pending, setPending] = useState(false);
  const [error, setError] = useState("");
  const [sentTo, setSentTo] = useState<string | null>(null);
  const goToApp = useCallback(() => router.replace("/dashboard"), [router]);

  async function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    const email = String(form.get("email")).trim();
    const password = String(form.get("password"));
    if (password !== String(form.get("confirm"))) {
      setError("The passwords do not match.");
      return;
    }
    setPending(true);
    setError("");
    const { response, error: problem } = await api.POST("/auth/signup", { body: { email, password } });
    setPending(false);
    if (response.ok) setSentTo(email);
    else setError(problemMessage(problem, "Could not create your account. Please try again."));
  }

  if (sentTo) {
    return (
      <div className="flex flex-col gap-4">
        <h1 className="text-xl font-semibold tracking-tight">Check your email</h1>
        <FormNotice>
          We sent a verification link to <strong>{sentTo}</strong>. Open it to activate your account, then
          sign in.
        </FormNotice>
        <Link href="/login" className="text-sm underline underline-offset-4">
          Go to sign in
        </Link>
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-4">
      <h1 className="text-xl font-semibold tracking-tight">Create your account</h1>
      <form onSubmit={onSubmit} className="flex flex-col gap-4">
        <Field id="email" label="Email" type="email" autoComplete="email" required maxLength={254} />
        <Field
          id="password"
          label="Password"
          type="password"
          autoComplete="new-password"
          required
          minLength={10}
          maxLength={72}
          hint="At least 10 characters."
        />
        <Field
          id="confirm"
          label="Confirm password"
          type="password"
          autoComplete="new-password"
          required
          minLength={10}
          maxLength={72}
        />
        <FormError>{error}</FormError>
        <Button type="submit" size="lg" disabled={pending}>
          {pending ? "Creating account…" : "Create account"}
        </Button>
      </form>
      <GoogleButton onSignedIn={goToApp} onError={setError} />
      <p className="text-sm">
        Already have an account?{" "}
        <Link href="/login" className="underline underline-offset-4">
          Sign in
        </Link>
      </p>
    </div>
  );
}
