"use client";

import { useQueryClient } from "@tanstack/react-query";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState, type FormEvent } from "react";

import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { problemMessage } from "@/features/auth/api-errors";
import { useAuth } from "@/features/auth/auth-provider";
import { FormError, FormNotice } from "@/features/auth/form-parts";
import { ApiProblem } from "@/features/profile/queries";
import { clearSession } from "@/lib/auth/session";

import { deleteAccount, fetchDataExport, saveBlob, useConsent, useSetAiConsent } from "./queries";

export const DELETE_PHRASE = "DELETE";

function messageOf(error: unknown, fallback: string) {
  if (error instanceof ApiProblem) {
    if (error.status === 429) return "You have asked for this too often. Please try again later.";
    return problemMessage(error.problem, fallback);
  }
  return fallback;
}

export function PrivacySettings() {
  return (
    <div className="flex flex-col gap-6">
      <AiProcessingCard />
      <ExportCard />
      <DeleteCard />
      <p className="text-sm text-muted-foreground">
        <Link href="/privacy" className="underline underline-offset-4">Privacy policy</Link>
        {" · "}
        <Link href="/terms" className="underline underline-offset-4">Terms of service</Link>
        {" · "}
        <Link href="/subprocessors" className="underline underline-offset-4">Subprocessors</Link>
        {" · "}
        <Link href="/cookies" className="underline underline-offset-4">Cookie notice</Link>
      </p>
    </div>
  );
}

function AiProcessingCard() {
  const { reloadUser } = useAuth();
  const consent = useConsent();
  const change = useSetAiConsent(reloadUser);
  const granted = consent.data?.aiProcessing === true;

  return (
    <Card>
      <CardHeader>
        <CardTitle>
          <h2>AI processing</h2>
        </CardTitle>
        <CardDescription>
          When on, JobFinder sends your CV content, profile and job descriptions to AI model providers to parse your CV,
          score jobs and draft documents. When off, no AI feature runs and nothing is sent. What is already stored stays
          until you delete it.
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-3">
        {consent.isPending && (
          <p role="status" className="text-sm text-muted-foreground">
            Loading…
          </p>
        )}
        {consent.isError && <FormError>We couldn&apos;t load your consent. Please reload the page.</FormError>}
        {consent.data && (
          <p role="status" className="text-sm">
            {granted ? (
              <>
                AI processing is <strong>on</strong>
                {consent.data.grantedAt ? ` (agreed ${new Date(consent.data.grantedAt).toLocaleDateString()})` : ""}.
              </>
            ) : (
              <>
                AI processing is <strong>off</strong>. CV upload and AI features are unavailable.
              </>
            )}
          </p>
        )}
        {change.isError && <FormError>{messageOf(change.error, "We couldn't save that. Please try again.")}</FormError>}
        {consent.data && (
          <Button
            className="self-start"
            variant={granted ? "outline" : "default"}
            disabled={change.isPending}
            onClick={() => change.mutate(!granted)}
          >
            {granted ? "Turn off AI processing" : "Turn on AI processing"}
          </Button>
        )}
      </CardContent>
    </Card>
  );
}

function ExportCard() {
  const [state, setState] = useState<"idle" | "working" | "done" | "failed">("idle");
  const [error, setError] = useState("");

  async function download() {
    setState("working");
    setError("");
    try {
      saveBlob(await fetchDataExport(), "jobfinder-data-export.zip");
      setState("done");
    } catch (e) {
      setError(messageOf(e, "We couldn't prepare your export. Please try again."));
      setState("failed");
    }
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>
          <h2>Download my data</h2>
        </CardTitle>
        <CardDescription>
          A zip with everything we store about you: your account, profile, CVs (the files you uploaded), generated
          documents, applications, interviews, notifications and billing records, as JSON files and original files.
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-3">
        <FormError>{error}</FormError>
        {state === "done" && <FormNotice>Your export was downloaded.</FormNotice>}
        <Button className="self-start" variant="outline" onClick={() => void download()} disabled={state === "working"}>
          {state === "working" ? "Preparing your export…" : "Download my data"}
        </Button>
      </CardContent>
    </Card>
  );
}

function DeleteCard() {
  const router = useRouter();
  const queryClient = useQueryClient();
  const [open, setOpen] = useState(false);
  const [phrase, setPhrase] = useState("");
  const [pending, setPending] = useState(false);
  const [error, setError] = useState("");

  async function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (phrase !== DELETE_PHRASE) return;
    setPending(true);
    setError("");
    try {
      await deleteAccount();
    } catch (e) {
      setPending(false);
      setError(messageOf(e, "We couldn't delete your account, and nothing was deleted. Please try again."));
      return;
    }
    clearSession();
    queryClient.clear();
    router.replace("/");
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>
          <h2>Delete my account</h2>
        </CardTitle>
        <CardDescription>
          Permanently erases your account and everything stored about you: profile, CVs and their files, generated
          documents, applications, interviews and settings. A paid subscription is cancelled. This cannot be undone, so
          download your data first if you want a copy.
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-3">
        {!open ? (
          <Button className="self-start" variant="destructive" onClick={() => setOpen(true)}>
            Delete my account…
          </Button>
        ) : (
          <form onSubmit={onSubmit} className="flex flex-col gap-3">
            <div className="flex flex-col gap-1.5">
              <Label htmlFor="delete-confirm">
                Type {DELETE_PHRASE} to confirm
              </Label>
              <Input
                id="delete-confirm"
                value={phrase}
                onChange={(event) => setPhrase(event.target.value)}
                autoComplete="off"
                spellCheck={false}
              />
            </div>
            <FormError>{error}</FormError>
            <div className="flex flex-wrap gap-2">
              <Button type="submit" variant="destructive" disabled={phrase !== DELETE_PHRASE || pending}>
                {pending ? "Deleting…" : "Permanently delete my account"}
              </Button>
              <Button type="button" variant="outline" onClick={() => { setOpen(false); setPhrase(""); setError(""); }} disabled={pending}>
                Cancel
              </Button>
            </div>
          </form>
        )}
      </CardContent>
    </Card>
  );
}
