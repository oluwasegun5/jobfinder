"use client";

import { useRouter } from "next/navigation";

import { PreferencesForm } from "@/features/profile/preferences-form";

/** Step 3. Saving (even with everything left empty) completes onboarding. */
export function PreferencesStep() {
  const router = useRouter();

  return (
    <div className="flex flex-col gap-6">
      <div className="flex flex-col gap-1">
        <h1 className="text-2xl font-semibold tracking-tight">Set your preferences</h1>
        <p className="text-sm text-muted-foreground">
          Tell us what you&apos;re after. All of it is optional and you can change it any time from your profile.
        </p>
      </div>
      <PreferencesForm submitLabel="Finish" onSaved={() => router.push("/dashboard")} />
    </div>
  );
}
