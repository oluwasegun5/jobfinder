import type { Metadata } from "next";

import { Subprocessors } from "@/features/legal/subprocessors";

export const metadata: Metadata = { title: "Subprocessors" };

export default function Page() {
  return <Subprocessors />;
}
