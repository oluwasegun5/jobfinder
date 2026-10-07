import type { Metadata } from "next";

import { PrivacyPolicy } from "@/features/legal/privacy-policy";

export const metadata: Metadata = { title: "Privacy policy" };

export default function Page() {
  return <PrivacyPolicy />;
}
