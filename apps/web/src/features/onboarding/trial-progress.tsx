"use client";

import { useBillingMe } from "@/features/billing/queries";
import { cn } from "@/lib/utils";

/** What each trial milestone is called for the user. The credits come from the API, never from here. */
const MILESTONE_LABELS: Record<string, string> = {
  EMAIL_VERIFIED: "Email verified",
  CV_PARSED: "CV read",
  ONBOARDING_COMPLETED: "Profile and preferences saved",
  MATCHES_VIEWED: "Matches viewed",
};

/** Said while a milestone is locked, where it has a rule the user cannot guess. */
const MILESTONE_HINTS: Record<string, string> = {
  MATCHES_VIEWED: "unlocks when you view your matches on a later day than you signed up",
};

const credits = (n: number | undefined = 0) => `${n.toLocaleString("en")} ${n === 1 ? "credit" : "credits"}`;

/**
 * The trial credit milestones (docs/adr/0042, 0045): which are unlocked, what each releases and the total, from the
 * billing status. It says only what the API reports: a trial on hold shows what is unlocked and that the rest is being
 * confirmed, a denied trial is promised nothing and is never told why. Nothing is shown for an account from before the
 * trial, while loading, or when the status cannot be read.
 */
export function TrialProgress() {
  const me = useBillingMe();
  const trial = me.data?.trial;
  if (!trial) return null;

  const tranches = trial.tranches ?? [];
  const unlocked = tranches.filter((t) => t.unlocked);
  const unlockedCredits = (trial.unlocked ?? 0).toLocaleString("en");
  const totalCredits = tranches.reduce((sum, t) => sum + (t.credits ?? 0), 0).toLocaleString("en");

  return (
    <section aria-label="Trial credits" className="flex flex-col gap-3 rounded-xl border bg-card p-4 text-card-foreground">
      <h2 className="text-sm font-semibold">Trial credits</h2>

      {trial.state === "DENIED" ? (
        <p className="text-sm text-muted-foreground">
          Free trial credits aren&apos;t available for this account. Your account works normally, and you can buy credits to
          continue.
        </p>
      ) : (
        <>
          <ul aria-label="Trial credit milestones" className="flex flex-col gap-2">
            {(trial.state === "HOLD" ? unlocked : tranches).map((t) => (
              <li key={t.milestone} className="flex items-start gap-2 text-sm">
                <span
                  aria-hidden
                  className={cn(
                    "mt-0.5 flex size-5 shrink-0 items-center justify-center rounded-full border text-xs",
                    t.unlocked ? "border-success bg-success text-success-foreground" : "border-border text-muted-foreground",
                  )}
                >
                  {t.unlocked ? "✓" : ""}
                </span>
                <span className="flex flex-col">
                  <span className={cn(t.unlocked ? "font-medium" : "text-muted-foreground")}>
                    {MILESTONE_LABELS[t.milestone ?? ""] ?? t.milestone}
                    <span className="font-normal text-muted-foreground"> · {credits(t.credits)}</span>
                  </span>
                  <span className="text-xs text-muted-foreground">
                    {t.unlocked ? "Unlocked" : "Not yet unlocked"}
                    {!t.unlocked && MILESTONE_HINTS[t.milestone ?? ""] ? `, ${MILESTONE_HINTS[t.milestone ?? ""]}` : ""}
                  </span>
                </span>
              </li>
            ))}
          </ul>

          {trial.state === "HOLD" ? (
            <>
              <p className="text-sm font-medium">{unlockedCredits} trial credits unlocked</p>
              <p className="text-sm text-muted-foreground">
                Your remaining trial credits are being confirmed, usually within 24 hours. You can keep going or upgrade now.
              </p>
            </>
          ) : (
            <p className="text-sm font-medium">
              {unlockedCredits} of {totalCredits} trial credits unlocked
            </p>
          )}
        </>
      )}
    </section>
  );
}
