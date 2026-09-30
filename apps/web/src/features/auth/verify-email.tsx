"use client";

import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { useEffect, useRef, useState } from "react";

import { api } from "@/lib/api";

import { problemMessage } from "./api-errors";
import { FormError, FormNotice } from "./form-parts";

type Result = { state: "verifying" } | { state: "done" } | { state: "failed"; message: string };

export function VerifyEmail() {
  const token = useSearchParams().get("token");
  const started = useRef(false);
  const [result, setResult] = useState<Result>(
    token ? { state: "verifying" } : { state: "failed", message: "This verification link is incomplete." },
  );

  useEffect(() => {
    // Tokens are single-use, so guard against React strict mode running the effect twice.
    if (!token || started.current) return;
    started.current = true;
    api.POST("/auth/verify-email", { body: { token } }).then(({ response, error }) => {
      setResult(
        response.ok
          ? { state: "done" }
          : { state: "failed", message: problemMessage(error, "This link is invalid or has expired.") },
      );
    });
  }, [token]);

  return (
    <div className="flex flex-col gap-4">
      <h1 className="text-xl font-semibold tracking-tight">Verify your email</h1>
      {result.state === "verifying" && <FormNotice>Verifying your email…</FormNotice>}
      {result.state === "done" && <FormNotice>Your email is verified. You can sign in now.</FormNotice>}
      {result.state === "failed" && <FormError>{result.message}</FormError>}
      <Link href="/login" className="text-sm underline underline-offset-4">
        {result.state === "failed" ? "Back to sign in to request a new link" : "Go to sign in"}
      </Link>
    </div>
  );
}
