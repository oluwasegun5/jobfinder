"use client";

import { usePathname } from "next/navigation";

import { Stepper } from "./stepper";
import { TrialProgress } from "./trial-progress";

/** The stepper, with the current step read from the URL so a reload or the back button stays in step. */
export function OnboardingProgress() {
  const pathname = usePathname();
  const current = pathname.endsWith("/preferences") ? 2 : pathname.endsWith("/review") ? 1 : 0;
  return (
    <div className="flex flex-col gap-4">
      <Stepper current={current} />
      <TrialProgress />
    </div>
  );
}
