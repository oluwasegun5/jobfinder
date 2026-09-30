"use client";

import Link from "next/link";
import { useState, type FormEvent } from "react";

import { Button } from "@/components/ui/button";
import { api } from "@/lib/api";

import { problemMessage } from "./api-errors";
import { Field, FormError, FormNotice } from "./form-parts";

export function ForgotPasswordForm() {
  const [pending, setPending] = useState(false);
  const [error, setError] = useState("");
  const [sent, setSent] = useState(false);

  async function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const email = String(new FormData(event.currentTarget).get("email")).trim();
    setPending(true);
    setError("");
    const { response, error: problem } = await api.POST("/auth/forgot-password", { body: { email } });
    setPending(false);
    if (response.ok) setSent(true);
    else setError(problemMessage(problem));
  }

  if (sent) {
    return (
      <div className="flex flex-col gap-4">
        <h1 className="text-xl font-semibold tracking-tight">Check your email</h1>
        <FormNotice>If an account exists for that address, a reset link is on its way.</FormNotice>
        <Link href="/login" className="text-sm underline underline-offset-4">
          Back to sign in
        </Link>
      </div>
    );
  }

  return (
    <form onSubmit={onSubmit} className="flex flex-col gap-4">
      <h1 className="text-xl font-semibold tracking-tight">Reset your password</h1>
      <Field id="email" label="Email" type="email" autoComplete="email" required maxLength={254} />
      <FormError>{error}</FormError>
      <Button type="submit" size="lg" disabled={pending}>
        {pending ? "Sending…" : "Send reset link"}
      </Button>
      <Link href="/login" className="text-sm underline underline-offset-4">
        Back to sign in
      </Link>
    </form>
  );
}
