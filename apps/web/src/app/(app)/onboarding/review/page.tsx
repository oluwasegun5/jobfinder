import type { Metadata } from "next";

import { ReviewStep } from "@/features/onboarding/review-step";

export const metadata: Metadata = { title: "Review your profile" };

export default function Page() {
  return <ReviewStep />;
}
