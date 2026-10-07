import type { Metadata } from "next";

import { PrivacySettings } from "@/features/privacy/privacy-settings";

export const metadata: Metadata = { title: "Privacy and data" };

export default function Page() {
  return (
    <div className="mx-auto flex max-w-3xl flex-col gap-6">
      <h1 className="text-2xl font-semibold tracking-tight">Privacy and data</h1>
      <PrivacySettings />
    </div>
  );
}
