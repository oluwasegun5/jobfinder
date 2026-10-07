"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import type { ReactNode } from "react";

import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { problemMessage } from "@/features/auth/api-errors";
import { FormError } from "@/features/auth/form-parts";
import { useAuth } from "@/features/auth/auth-provider";
import { ApiProblem } from "@/features/profile/queries";

import { useSetAiConsent } from "./queries";

/** The page where consent is managed stays reachable without it: that is where it is given back, or the account deleted. */
export const PRIVACY_SETTINGS_PATH = "/settings/privacy";

/**
 * Accounts created with Google, and accounts that existed before consent was recorded, have not agreed to AI
 * processing. Until they do, the signed-in pages show this instead of features that would only fail.
 */
export function ConsentGate({ children }: { children: ReactNode }) {
  const { user, reloadUser, signOut } = useAuth();
  const pathname = usePathname();
  const consent = useSetAiConsent(reloadUser);

  if (!user || user.aiConsent || pathname.startsWith(PRIVACY_SETTINGS_PATH)) return children;

  const error = consent.error instanceof ApiProblem ? problemMessage(consent.error.problem) : consent.error ? "Please try again." : "";
  return (
    <div className="mx-auto flex max-w-xl flex-col gap-4 py-8">
      <Card>
        <CardHeader>
          <CardTitle>
            <h1>Allow AI processing to continue</h1>
          </CardTitle>
          <CardDescription>
            JobFinder reads your CV, scores jobs and drafts tailored documents with AI model providers. To do that it
            sends the relevant text (your CV content, profile and the job description) to them. Nothing is sent
            without your agreement, and you can withdraw it at any time in Privacy and data.
          </CardDescription>
        </CardHeader>
        <CardContent className="flex flex-col gap-4">
          <FormError>{error}</FormError>
          <div className="flex flex-wrap gap-2">
            <Button onClick={() => consent.mutate(true)} disabled={consent.isPending}>
              {consent.isPending ? "Saving…" : "I agree to AI processing"}
            </Button>
            <Button variant="outline" onClick={() => void signOut()}>
              Sign out
            </Button>
          </div>
          <p className="text-sm text-muted-foreground">
            Read the <Link href="/privacy" className="underline underline-offset-4">privacy policy</Link> and the{" "}
            <Link href="/subprocessors" className="underline underline-offset-4">list of providers</Link>. You can also{" "}
            <Link href={PRIVACY_SETTINGS_PATH} className="underline underline-offset-4">download or delete your data</Link>{" "}
            instead.
          </p>
        </CardContent>
      </Card>
    </div>
  );
}
