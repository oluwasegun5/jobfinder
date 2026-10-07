import type { Metadata } from "next";

import { CookieNotice } from "@/features/legal/cookie-notice";

export const metadata: Metadata = { title: "Cookie notice" };

export default function Page() {
  return <CookieNotice />;
}
