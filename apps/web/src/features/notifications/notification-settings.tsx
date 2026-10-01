"use client";

import Link from "next/link";
import { useState, type FormEvent } from "react";

import { Button } from "@/components/ui/button";
import { problemFieldErrors, problemMessage } from "@/features/auth/api-errors";
import { FormError, FormNotice } from "@/features/auth/form-parts";
import { CheckboxField, Field, Section, SelectField } from "@/features/profile/fields";
import { ApiProblem } from "@/features/profile/queries";

import {
  useNotificationPreferences,
  useSaveNotificationPreferences,
  type NotificationPreferences,
  type NotificationPreferencesRequest,
} from "./queries";

const WEEKDAYS = ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"];
const HOURS = Array.from({ length: 24 }, (_, hour) => hour);
const hourLabel = (hour: number) => `${String(hour).padStart(2, "0")}:00`;

function browserZone(): string {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone || "UTC";
  } catch {
    return "UTC";
  }
}

function knownZones(): string[] {
  try {
    return ["UTC", ...Intl.supportedValuesOf("timeZone").filter((zone) => zone.includes("/"))];
  } catch {
    return ["UTC"];
  }
}

type FormState = {
  emailEnabled: boolean;
  digestEnabled: boolean;
  digestFrequency: "DAILY" | "WEEKLY";
  digestHour: number;
  digestWeekday: number;
  timezone: string;
  instantEnabled: boolean;
  instantThreshold: number;
};

function toState(preferences: NotificationPreferences): FormState {
  return {
    emailEnabled: preferences.emailEnabled ?? true,
    digestEnabled: preferences.digestEnabled ?? false,
    digestFrequency: preferences.digestFrequency ?? "DAILY",
    digestHour: preferences.digestHour ?? 8,
    digestWeekday: preferences.digestWeekday ?? 1,
    // Someone who never chose a zone gets their own, so "08:00" means their morning and not UTC's.
    timezone: preferences.timezone ?? browserZone(),
    instantEnabled: preferences.instantEnabled ?? false,
    instantThreshold: preferences.instantThreshold ?? 85,
  };
}

export function toRequest(state: FormState): NotificationPreferencesRequest {
  return { ...state, timezone: state.timezone.trim() === "" ? undefined : state.timezone.trim() };
}

export function NotificationSettings() {
  const preferences = useNotificationPreferences();

  if (preferences.isPending) {
    return (
      <p role="status" className="text-sm text-muted-foreground">
        Loading your notification settings…
      </p>
    );
  }
  if (preferences.isError) {
    return (
      <div className="flex flex-col gap-3">
        <FormError>We couldn&apos;t load your notification settings.</FormError>
        <Button variant="outline" className="self-start" onClick={() => void preferences.refetch()}>
          Try again
        </Button>
      </div>
    );
  }
  return <Form initial={preferences.data} />;
}

function Form({ initial }: { initial: NotificationPreferences }) {
  // Initialised once so a background refetch never overwrites what the user is choosing.
  const [state, setState] = useState<FormState>(() => toState(initial));
  const [errors, setErrors] = useState<string[]>([]);
  const [saved, setSaved] = useState(false);
  const save = useSaveNotificationPreferences();
  const zones = knownZones();

  const set = <K extends keyof FormState>(field: K, value: FormState[K]) => {
    setSaved(false);
    setState((current) => ({ ...current, [field]: value }));
  };

  async function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setErrors([]);
    setSaved(false);
    try {
      await save.mutateAsync(toRequest(state));
    } catch (error) {
      const problem = error instanceof ApiProblem ? error.problem : undefined;
      const fields = problemFieldErrors(problem);
      setErrors(fields.length > 0 ? fields : [problemMessage(problem, "We couldn't save your settings. Please try again.")]);
      return;
    }
    setSaved(true);
  }

  const unsubscribedFromDigests = initial.digestsUnsubscribed || initial.allUnsubscribed;

  return (
    <form onSubmit={onSubmit} className="flex flex-col gap-6" aria-label="Notification settings">
      <Section title="Email">
        <CheckboxField
          id="emailEnabled"
          label="Send me emails from JobFinder"
          hint="Turn this off to stop every digest and alert. Account emails, such as password resets, are always sent."
          checked={state.emailEnabled}
          onChange={(e) => set("emailEnabled", e.target.checked)}
        />
        {(unsubscribedFromDigests || initial.allUnsubscribed) && (
          <FormNotice>
            You unsubscribed from {initial.allUnsubscribed ? "optional emails" : "digests"} with a link in an email.
            Turning a switch below on again lets them through.
          </FormNotice>
        )}
      </Section>

      <Section title="Digest of your best new matches">
        <CheckboxField
          id="digestEnabled"
          label="Email me a digest"
          hint="Only new strong matches you have not seen in an earlier email. Nothing is sent on a day with nothing new."
          checked={state.digestEnabled}
          onChange={(e) => set("digestEnabled", e.target.checked)}
        />
        <div className="grid gap-4 sm:grid-cols-3">
          <SelectField
            id="digestFrequency"
            label="How often"
            value={state.digestFrequency}
            disabled={!state.digestEnabled}
            onChange={(e) => set("digestFrequency", e.target.value as FormState["digestFrequency"])}
          >
            <option value="DAILY">Every day</option>
            <option value="WEEKLY">Every week</option>
          </SelectField>
          {state.digestFrequency === "WEEKLY" && (
            <SelectField
              id="digestWeekday"
              label="On"
              value={state.digestWeekday}
              disabled={!state.digestEnabled}
              onChange={(e) => set("digestWeekday", Number(e.target.value))}
            >
              {WEEKDAYS.map((name, index) => (
                <option key={name} value={index + 1}>
                  {name}
                </option>
              ))}
            </SelectField>
          )}
          <SelectField
            id="digestHour"
            label="At"
            value={state.digestHour}
            disabled={!state.digestEnabled}
            onChange={(e) => set("digestHour", Number(e.target.value))}
          >
            {HOURS.map((hour) => (
              <option key={hour} value={hour}>
                {hourLabel(hour)}
              </option>
            ))}
          </SelectField>
        </div>
        <Field
          id="timezone"
          label="Your time zone"
          list="timezones"
          hint="A region such as Africa/Lagos. Times and dates in the emails use it."
          value={state.timezone}
          maxLength={64}
          onChange={(e) => set("timezone", e.target.value)}
        />
        <datalist id="timezones">
          {zones.map((zone) => (
            <option key={zone} value={zone} />
          ))}
        </datalist>
      </Section>

      <Section title="Instant alerts">
        <CheckboxField
          id="instantEnabled"
          label="Alert me as soon as a strong match appears"
          hint="At most three alert emails a day, each with up to five jobs."
          checked={state.instantEnabled}
          onChange={(e) => set("instantEnabled", e.target.checked)}
        />
        <Field
          id="instantThreshold"
          label="Lowest match score that alerts"
          type="number"
          min={50}
          max={100}
          step={1}
          value={state.instantThreshold}
          disabled={!state.instantEnabled}
          hint="From 50 to 100. 85 means only very strong matches."
          onChange={(e) => set("instantThreshold", Number(e.target.value))}
        />
      </Section>

      <p className="text-sm text-muted-foreground">
        Emails for a saved search follow that search&apos;s own setting.{" "}
        <Link href="/saved-searches" className="underline">
          Manage saved searches
        </Link>
      </p>

      {errors.length > 0 && (
        <div role="alert" className="flex flex-col gap-1 rounded-lg bg-destructive/10 px-3 py-2 text-sm text-destructive">
          <p>We couldn&apos;t save your settings:</p>
          <ul className="list-disc pl-5">
            {errors.slice(0, 6).map((message) => (
              <li key={message}>{message}</li>
            ))}
          </ul>
        </div>
      )}
      {saved && <FormNotice>Saved.</FormNotice>}
      <Button type="submit" size="lg" disabled={save.isPending} className="self-start">
        {save.isPending ? "Saving…" : "Save settings"}
      </Button>
    </form>
  );
}
