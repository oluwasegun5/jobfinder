import type { Metadata } from "next";

import { ResumeManager } from "@/features/profile/resume-manager";

export const metadata: Metadata = { title: "Your CVs" };

export default function Page() {
  return <ResumeManager />;
}
