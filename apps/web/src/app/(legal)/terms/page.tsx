import type { Metadata } from "next";

import { TermsOfService } from "@/features/legal/terms-of-service";

export const metadata: Metadata = { title: "Terms of service" };

export default function Page() {
  return <TermsOfService />;
}
