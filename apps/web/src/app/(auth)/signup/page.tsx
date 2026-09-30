import type { Metadata } from "next";

import { SignupForm } from "@/features/auth/signup-form";

export const metadata: Metadata = { title: "Create account" };

export default function Page() {
  return <SignupForm />;
}
