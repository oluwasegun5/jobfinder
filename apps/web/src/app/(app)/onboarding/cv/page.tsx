import type { Metadata } from "next";

import { CvStep } from "@/features/onboarding/cv-step";

export const metadata: Metadata = { title: "Upload your CV" };

export default function Page() {
  return <CvStep />;
}
