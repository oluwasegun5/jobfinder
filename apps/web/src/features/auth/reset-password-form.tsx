"use client";

import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { useState, type FormEvent } from "react";

import { Button } from "@/components/ui/button";
import { api } from "@/lib/api";

import { problemMessage } from "./api-errors";
import { Field, FormError, FormNotice } from "./form-parts";

export function ResetPasswordForm() {
  const token = useSearchParams().get("token");
  const [pending, setPending] = useState(false);
  const [error, setError] = useState("");
  const [done, setDone] = useState(false);

  async function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!token) return;
    const form = new FormData(event.currentTarget);
    const newPassword = String(form.get("password"));
    if (newPassword !== String(form.get("confirm"))) {
      setError("The passwords do not match.");
      return;
    }
    setPending(true);
    setError("");
    const { response, error: problem } = await api.POST("/auth/reset-password", {
      body: { token, newPassword },
    });
    setPending(false);
    if (response.ok) setDone(true);
    else setError(problemMessage(problem, "This link is invalid or has expired."));
  }

  if (!token) {
    return (
      <div className="flex flex-col gap-4">
        <h1 className="text-xl font-semibold tracking-tight">Choose a new password</h1>
        <FormError>This reset link is incomplete.</FormError>
        <Link href="/forgot-password" className="text-sm underline underline-offset-4">
          Request a new link
        </Link>
      </div>
    );
  }

  if (done) {
    return (
      <div className="flex flex-col gap-4">
        <h1 className="text-xl font-semibold tracking-tight">Password changed</h1>
        <FormNotice>Your password has been changed. Sign in with the new one.</FormNotice>
        <Link href="/login" className="text-sm underline underline-offset-4">
          Go to sign in
        </Link>
      </div>
    );
  }

  return (
    <form onSubmit={onSubmit} className="flex flex-col gap-4">
      <h1 className="text-xl font-semibold tracking-tight">Choose a new password</h1>
      <Field
        id="password"
        label="New password"
        type="password"
        autoComplete="new-password"
        required
        minLength={10}
        maxLength={72}
        hint="At least 10 characters."
      />
      <Field
        id="confirm"
        label="Confirm new password"
        type="password"
        autoComplete="new-password"
        required
        minLength={10}
        maxLength={72}
      />
      <FormError>{error}</FormError>
      <Button type="submit" size="lg" disabled={pending}>
        {pending ? "Saving…" : "Change password"}
      </Button>
    </form>
  );
}
