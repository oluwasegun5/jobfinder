"use client";

import Link from "next/link";
import { useSearchParams } from "next/navigation";

import { Button } from "@/components/ui/button";
import { problemCode } from "@/features/auth/api-errors";
import { FormError } from "@/features/auth/form-parts";
import { ApiProblem } from "@/features/profile/queries";

import { useUnsubscribe, useUnsubscribeInfo, type UnsubscribeInfo } from "./queries";

function what(info: UnsubscribeInfo): string {
  switch (info.scope) {
    case "SAVED_SEARCH":
      return info.savedSearchName ? `emails for your saved search "${info.savedSearchName}"` : "emails for this saved search";
    case "DIGESTS":
      return "digest emails";
    case "INSTANT_ALERTS":
      return "instant job alerts";
    default: // MARKETING: every optional email
      return "all optional emails from JobFinder";
  }
}

/**
 * The page behind the unsubscribe link in an email. Public: the token in the address is the credential. It first says
 * what the link would do (a mail scanner opening the link changes nothing), and only a click on the button does it.
 */
export function Unsubscribe() {
  const token = useSearchParams().get("token") ?? "";
  const info = useUnsubscribeInfo(token);
  const unsubscribe = useUnsubscribe(token);

  if (token === "" || (info.isError && info.error instanceof ApiProblem && problemCode(info.error.problem) === "invalid_unsubscribe_link")) {
    return (
      <div className="flex flex-col gap-3">
        <h1 className="text-xl font-semibold tracking-tight">This link doesn&apos;t work</h1>
        <p className="text-sm text-muted-foreground">
          It may be incomplete or too old. You can switch emails off yourself in your notification settings.
        </p>
        <Link href="/settings/notifications" className="text-sm underline">
          Open notification settings
        </Link>
      </div>
    );
  }
  if (info.isPending) {
    return (
      <p role="status" className="text-sm text-muted-foreground">
        Checking your link…
      </p>
    );
  }
  if (info.isError) {
    return (
      <div className="flex flex-col gap-3">
        <FormError>We couldn&apos;t check this link. Please try again.</FormError>
        <Button variant="outline" className="self-start" onClick={() => void info.refetch()}>
          Try again
        </Button>
      </div>
    );
  }
  if (unsubscribe.isSuccess) {
    return (
      <div className="flex flex-col gap-3">
        <h1 className="text-xl font-semibold tracking-tight">You&apos;re unsubscribed</h1>
        <p role="status" className="text-sm">
          You will no longer get {what(unsubscribe.data)}.
        </p>
        <p className="text-sm text-muted-foreground">
          You can change this any time in your{" "}
          <Link href="/settings/notifications" className="underline">
            notification settings
          </Link>
          . Emails about your account, such as password resets, are not affected.
        </p>
      </div>
    );
  }
  return (
    <div className="flex flex-col gap-4">
      <h1 className="text-xl font-semibold tracking-tight">Unsubscribe</h1>
      <p className="text-sm">Stop {what(info.data)}?</p>
      <FormError>{unsubscribe.isError ? "That didn't work. Please try again." : undefined}</FormError>
      <Button className="self-start" disabled={unsubscribe.isPending} onClick={() => unsubscribe.mutate()}>
        {unsubscribe.isPending ? "Unsubscribing…" : "Unsubscribe"}
      </Button>
    </div>
  );
}
