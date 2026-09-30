"use client";

import { useState, type FormEvent } from "react";

import { Button } from "@/components/ui/button";
import { problemFieldErrors, problemMessage } from "@/features/auth/api-errors";
import { FormError, FormNotice } from "@/features/auth/form-parts";

import { CheckboxField, Field, Section, TextAreaField } from "./fields";
import { ApiProblem, usePreferences, useSavePreferences, type Preferences, type PreferencesRequest } from "./queries";

const WORK_MODES = [
  { value: "REMOTE", label: "Remote" },
  { value: "HYBRID", label: "Hybrid" },
  { value: "ONSITE", label: "On-site" },
] as const;

const CURRENCIES = ["USD", "EUR", "GBP", "NGN", "CAD", "AUD", "INR", "ZAR", "KES"];

type FormState = {
  targetTitles: string;
  locations: string;
  workModes: string[];
  minSalary: string;
  currency: string;
  needsSponsorship: boolean;
  excludedCompanies: string;
  excludedIndustries: string;
};

const entries = (text: string) =>
  text
    .split(/[\n,]/)
    .map((part) => part.trim())
    .filter(Boolean);

function toState(preferences: Preferences | undefined): FormState {
  return {
    targetTitles: (preferences?.targetTitles ?? []).join("\n"),
    locations: (preferences?.locations ?? []).join("\n"),
    workModes: preferences?.workModes ?? [],
    minSalary: preferences?.minSalary == null ? "" : String(preferences.minSalary),
    currency: preferences?.currency ?? "",
    needsSponsorship: preferences?.needsSponsorship ?? false,
    excludedCompanies: (preferences?.excludedCompanies ?? []).join("\n"),
    excludedIndustries: (preferences?.excludedIndustries ?? []).join("\n"),
  };
}

export function toPreferencesRequest(state: FormState): PreferencesRequest {
  const salary = state.minSalary.trim();
  return {
    targetTitles: entries(state.targetTitles),
    locations: entries(state.locations),
    workModes: state.workModes as PreferencesRequest["workModes"],
    minSalary: salary === "" ? undefined : Number(salary),
    currency: state.currency.trim() === "" ? undefined : state.currency.trim().toUpperCase(),
    needsSponsorship: state.needsSponsorship,
    excludedCompanies: entries(state.excludedCompanies),
    excludedIndustries: entries(state.excludedIndustries),
  };
}

export function PreferencesForm({ submitLabel, onSaved }: { submitLabel: string; onSaved?: () => void }) {
  const preferences = usePreferences();

  if (preferences.isPending) {
    return (
      <p role="status" className="text-sm text-muted-foreground">
        Loading your preferences…
      </p>
    );
  }
  if (preferences.isError) {
    return (
      <div className="flex flex-col gap-3">
        <FormError>We couldn&apos;t load your preferences.</FormError>
        <Button variant="outline" className="self-start" onClick={() => void preferences.refetch()}>
          Try again
        </Button>
      </div>
    );
  }
  return <Form initial={preferences.data} submitLabel={submitLabel} onSaved={onSaved} />;
}

function Form({ initial, submitLabel, onSaved }: { initial: Preferences; submitLabel: string; onSaved?: () => void }) {
  // Initialised once so a background refetch never overwrites what the user is typing.
  const [state, setState] = useState<FormState>(() => toState(initial));
  const [errors, setErrors] = useState<string[]>([]);
  const [saved, setSaved] = useState(false);
  const save = useSavePreferences();

  const set = <K extends keyof FormState>(field: K, value: FormState[K]) => {
    setSaved(false);
    setState((current) => ({ ...current, [field]: value }));
  };

  async function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setErrors([]);
    setSaved(false);
    try {
      await save.mutateAsync(toPreferencesRequest(state));
    } catch (error) {
      const problem = error instanceof ApiProblem ? error.problem : undefined;
      const fields = problemFieldErrors(problem);
      setErrors(fields.length > 0 ? fields : [problemMessage(problem, "We couldn't save your preferences. Please try again.")]);
      return;
    }
    setSaved(true);
    onSaved?.();
  }

  const salaryGiven = state.minSalary.trim() !== "";

  return (
    <form onSubmit={onSubmit} className="flex flex-col gap-6">
      <Section title="What are you looking for?">
        <TextAreaField id="targetTitles" label="Job titles" rows={3} value={state.targetTitles}
          hint="One per line, for example Backend Engineer"
          onChange={(e) => set("targetTitles", e.target.value)} />
        <TextAreaField id="locations" label="Locations" rows={3} value={state.locations}
          hint="Cities or countries, one per line"
          onChange={(e) => set("locations", e.target.value)} />
        <fieldset className="flex flex-col gap-2">
          <legend className="text-sm font-medium">Work mode</legend>
          <div className="flex flex-wrap gap-4">
            {WORK_MODES.map(({ value, label }) => (
              <CheckboxField key={value} id={`mode-${value}`} label={label} checked={state.workModes.includes(value)}
                onChange={(e) =>
                  set("workModes", e.target.checked ? [...state.workModes, value] : state.workModes.filter((m) => m !== value))
                } />
            ))}
          </div>
        </fieldset>
      </Section>

      <Section title="Pay and visa">
        <div className="grid gap-4 sm:grid-cols-2">
          <Field id="minSalary" label="Minimum yearly salary" type="number" min={0} max={100000000} step={1000}
            value={state.minSalary} onChange={(e) => set("minSalary", e.target.value)} />
          <Field id="currency" label="Currency" list="currencies" maxLength={3} placeholder="USD" value={state.currency}
            required={salaryGiven} pattern="[A-Za-z]{3}" title="A 3-letter code such as USD"
            onChange={(e) => set("currency", e.target.value.toUpperCase())} />
          <datalist id="currencies">
            {CURRENCIES.map((code) => (
              <option key={code} value={code} />
            ))}
          </datalist>
        </div>
        <CheckboxField id="needsSponsorship" label="I need visa sponsorship" checked={state.needsSponsorship}
          onChange={(e) => set("needsSponsorship", e.target.checked)} />
      </Section>

      <Section title="What to avoid">
        <TextAreaField id="excludedCompanies" label="Companies to exclude" rows={3} value={state.excludedCompanies}
          hint="One per line" onChange={(e) => set("excludedCompanies", e.target.value)} />
        <TextAreaField id="excludedIndustries" label="Industries to avoid" rows={3} value={state.excludedIndustries}
          hint="One per line" onChange={(e) => set("excludedIndustries", e.target.value)} />
      </Section>

      {errors.length > 0 && (
        <div role="alert" className="flex flex-col gap-1 rounded-lg bg-destructive/10 px-3 py-2 text-sm text-destructive">
          <p>We couldn&apos;t save your preferences:</p>
          <ul className="list-disc pl-5">
            {errors.slice(0, 6).map((message) => (
              <li key={message}>{message}</li>
            ))}
          </ul>
        </div>
      )}
      {saved && !onSaved && <FormNotice>Saved.</FormNotice>}
      <Button type="submit" size="lg" disabled={save.isPending} className="self-start">
        {save.isPending ? "Saving…" : submitLabel}
      </Button>
    </form>
  );
}
