import { Check, Minus } from "lucide-react";
import Link from "next/link";

import { SiteShell } from "@/components/layout/site-shell";
import { ScoreMeter } from "@/components/score-meter";
import { Badge } from "@/components/ui/badge";
import { buttonVariants } from "@/components/ui/button";
import { cn } from "@/lib/utils";

/** The workflow in the order a job seeker goes through it, so the numbering carries meaning. */
const steps: { title: string; description: string }[] = [
  {
    title: "Every job in one place",
    description: "Openings pulled from company career pages and job boards, de-duplicated and kept fresh.",
  },
  {
    title: "Matches that explain themselves",
    description: "A 0–100 score for each job, with the strengths and gaps behind it.",
  },
  {
    title: "A CV for every application",
    description: "Tailored resumes and cover letters built from your real experience — you approve every change.",
  },
  {
    title: "Track it all",
    description: "From saved to offer on one board, with reminders to follow up.",
  },
];

/** Illustrative only, and labelled as such on the page: it shows the shape of a match, not a real result. */
function ExampleMatch() {
  return (
    <figure className="flex flex-col gap-3">
      <div className="flex flex-col gap-5 rounded-2xl border bg-card p-5 text-card-foreground shadow-lg sm:p-6 dark:shadow-none">
        <div className="flex items-start justify-between gap-3">
          <div className="flex flex-col gap-1">
            <p className="text-lead font-semibold tracking-[-0.01em]">Backend engineer</p>
            <p className="text-sm text-muted-foreground">Hybrid, Lagos</p>
          </div>
          <Badge variant="highlight">Example</Badge>
        </div>
        <ScoreMeter score={82} />
        <div className="flex flex-col gap-4 border-t pt-4">
          <div className="flex flex-col gap-2">
            <p className="text-sm font-semibold">Why you match</p>
            <ul className="flex flex-col gap-1.5 text-sm">
              {["Five years of Java and Spring Boot", "Built and ran payment APIs", "Lives in Lagos"].map((item) => (
                <li key={item} className="flex gap-2">
                  <Check className="mt-0.5 size-4 shrink-0 text-success" aria-hidden />
                  {item}
                </li>
              ))}
            </ul>
          </div>
          <div className="flex flex-col gap-2">
            <p className="text-sm font-semibold">Gaps</p>
            <ul className="flex flex-col gap-1.5 text-sm">
              <li className="flex gap-2">
                <Minus className="mt-0.5 size-4 shrink-0 text-muted-foreground" aria-hidden />
                No Kubernetes on the CV
              </li>
            </ul>
          </div>
        </div>
      </div>
      <figcaption className="text-xs text-muted-foreground">
        An example of how a match is explained. Your scores come from your own profile.
      </figcaption>
    </figure>
  );
}

export default function LandingPage() {
  return (
    <SiteShell>
      <section className="mx-auto grid w-full max-w-6xl items-center gap-12 px-4 pt-12 pb-16 sm:px-6 sm:pt-20 lg:grid-cols-[1.15fr_0.85fr] lg:gap-16 lg:pb-24">
        <div className="flex flex-col items-start gap-6">
          <h1 className="max-w-[16ch] text-display font-[750] tracking-[-0.035em]">
            Find the right job, and apply with a CV made for it.
          </h1>
          <p className="max-w-[56ch] text-lead text-pretty text-muted-foreground">
            JobFinder gathers openings from across the web, ranks them against your profile and helps you tailor every
            application — without inventing a word of your experience.
          </p>
          <div className="flex w-full flex-col gap-3 sm:w-auto sm:flex-row">
            <Link href="/signup" className={buttonVariants({ size: "lg" })}>
              Get started
            </Link>
            <Link href="/login" className={buttonVariants({ size: "lg", variant: "outline" })}>
              Sign in
            </Link>
          </div>
        </div>
        <ExampleMatch />
      </section>

      <section aria-labelledby="how-heading" className="border-t bg-card">
        <div className="mx-auto flex w-full max-w-6xl flex-col gap-10 px-4 py-16 sm:px-6 lg:py-24">
          <h2 id="how-heading" className="max-w-[24ch] text-headline font-semibold">
            From the first search to the offer
          </h2>
          <ol className="grid gap-8 sm:grid-cols-2 lg:grid-cols-4 lg:gap-6">
            {steps.map(({ title, description }, index) => (
              <li key={title} className="flex gap-4 lg:flex-col lg:gap-3 lg:border-t-2 lg:border-primary lg:pt-5">
                <span
                  aria-hidden
                  className="flex size-9 shrink-0 items-center justify-center rounded-full bg-secondary text-sm font-semibold text-secondary-foreground tabular-nums"
                >
                  {index + 1}
                </span>
                <div className="flex flex-col gap-1.5">
                  <h3 className="text-base font-semibold">{title}</h3>
                  <p className="max-w-[40ch] text-sm text-muted-foreground">{description}</p>
                </div>
              </li>
            ))}
          </ol>
        </div>
      </section>

      <section aria-labelledby="promise-heading" className="mx-auto w-full max-w-6xl px-4 py-12 sm:px-6 lg:py-16">
        {/* Solid brand green in light mode; in dark mode a large mint block glares, so it becomes a bordered card. */}
        <div className="flex flex-col gap-8 rounded-2xl bg-primary p-6 text-primary-foreground sm:p-10 lg:flex-row lg:items-end lg:justify-between dark:border dark:bg-card dark:text-card-foreground">
          <div className="flex max-w-[60ch] flex-col gap-3">
            <h2 id="promise-heading" className="text-headline font-semibold">
              Your experience, never invented
            </h2>
            <p className="text-pretty opacity-90">
              Tailoring rephrases and reorders what is already in your CV to fit each job. It does not add skills or
              roles you do not have, and you see every change before you approve it.
            </p>
          </div>
          <Link
            href="/signup"
            className={cn(
              buttonVariants({ size: "lg" }),
              "bg-primary-foreground text-primary hover:bg-primary-foreground/90 focus-visible:outline-primary-foreground",
              "dark:bg-primary dark:text-primary-foreground dark:hover:bg-primary/90 dark:focus-visible:outline-ring",
            )}
          >
            Create your account
          </Link>
        </div>
      </section>
    </SiteShell>
  );
}
