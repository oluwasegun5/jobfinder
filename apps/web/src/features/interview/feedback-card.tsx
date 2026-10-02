"use client";

import { Check, X } from "lucide-react";

import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { cn } from "@/lib/utils";

import { RUBRIC, STAR_PARTS, formatScore, type Feedback } from "./model";

/** One rubric score as text first (screen readers and print) and a bar second. */
export function ScoreMeter({ label, hint, value, max = 5 }: { label: string; hint?: string; value: number | undefined; max?: number }) {
  const shown = value === undefined ? undefined : Math.min(Math.max(value, 0), max);
  return (
    <div className="flex flex-col gap-1">
      <div className="flex items-baseline justify-between gap-2">
        <dt className="text-sm font-medium">{label}</dt>
        <dd className="text-sm tabular-nums" aria-label={`${label}: ${formatScore(value)} out of ${max}`}>
          {formatScore(value)}
          <span className="text-muted-foreground"> / {max}</span>
        </dd>
      </div>
      <div className="h-1.5 overflow-hidden rounded-full bg-muted" aria-hidden>
        <div className="h-full rounded-full bg-primary" style={{ width: `${shown === undefined ? 0 : (shown / max) * 100}%` }} />
      </div>
      {hint && <p className="text-xs text-muted-foreground">{hint}</p>}
    </div>
  );
}

/** Quotes are the candidate's own words, taken verbatim from the answer; they are shown as quotes, never as claims. */
function Points({ title, items, tone }: { title: string; items: { text?: string; quote?: string }[] | undefined; tone: "good" | "work" }) {
  if (!items || items.length === 0) return null;
  return (
    <section aria-label={title} className="flex flex-col gap-2">
      <h4 className={cn("text-sm font-semibold", tone === "good" ? "text-emerald-700 dark:text-emerald-400" : "text-amber-700 dark:text-amber-400")}>
        {title}
      </h4>
      <ul className="flex flex-col gap-3">
        {items.map((item, index) => (
          <li key={`${item.text}-${index}`} className="flex flex-col gap-1 text-sm">
            <p>{item.text}</p>
            {item.quote && (
              <blockquote className="border-l-2 pl-3 text-muted-foreground italic">
                <q>{item.quote}</q>
              </blockquote>
            )}
          </li>
        ))}
      </ul>
    </section>
  );
}

/** The feedback on one answer: the four rubric scores, the STAR check for behavioural questions, and quoted points. */
export function FeedbackCard({ feedback, position }: { feedback: Feedback; position?: number }) {
  const star = feedback.star;
  const hasStar = star?.score !== undefined && star?.score !== null;
  return (
    <Card aria-label={position ? `Feedback on answer ${position}` : "Feedback on your answer"}>
      <CardHeader>
        <CardTitle>Feedback on your answer</CardTitle>
      </CardHeader>
      <CardContent className="flex flex-col gap-5">
        <dl className="grid gap-3 sm:grid-cols-2">
          {RUBRIC.map(({ key, label, hint }) => (
            <ScoreMeter key={key} label={label} hint={hint} value={feedback[key]} />
          ))}
        </dl>

        {hasStar && (
          <section aria-label="STAR check" className="flex flex-col gap-2">
            <h4 className="text-sm font-semibold">
              STAR check: <span className="tabular-nums">{star?.score}</span> / 5
            </h4>
            <ul className="flex flex-wrap gap-2">
              {STAR_PARTS.map(({ key, label }) => {
                const present = star?.[key] === true;
                return (
                  <li
                    key={key}
                    className={cn(
                      "inline-flex items-center gap-1 rounded-full border px-2.5 py-0.5 text-xs font-medium",
                      present ? "border-emerald-600/40 bg-emerald-500/10" : "border-border text-muted-foreground",
                    )}
                  >
                    {present ? <Check className="size-3" aria-hidden /> : <X className="size-3" aria-hidden />}
                    {label}
                    <span className="sr-only">{present ? " is in your answer" : " is missing from your answer"}</span>
                  </li>
                );
              })}
            </ul>
          </section>
        )}

        <Points title="What worked" items={feedback.strengths} tone="good" />
        <Points title="What to improve" items={feedback.improvements} tone="work" />
      </CardContent>
    </Card>
  );
}
