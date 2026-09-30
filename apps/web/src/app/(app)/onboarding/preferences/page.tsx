import type { Metadata } from "next";

import { PreferencesStep } from "@/features/onboarding/preferences-step";

export const metadata: Metadata = { title: "Set your preferences" };

export default function Page() {
  return <PreferencesStep />;
}
