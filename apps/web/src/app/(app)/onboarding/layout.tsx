import { Suspense } from "react";

import { OnboardingProgress } from "@/features/onboarding/onboarding-progress";

export default function OnboardingLayout({ children }: LayoutProps<"/">) {
  return (
    <div className="mx-auto flex max-w-3xl flex-col gap-8">
      <OnboardingProgress />
      <Suspense>{children}</Suspense>
    </div>
  );
}
