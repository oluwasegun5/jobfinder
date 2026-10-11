import type { Metadata } from "next";
import Link from "next/link";

import { buttonVariants } from "@/components/ui/button";
import { PricingPage } from "@/features/billing/pricing-page";
import { LegalLinks } from "@/features/legal/legal-page";

export const metadata: Metadata = {
  title: "Pricing",
  description: "Start with a free trial, then choose a monthly plan or add credits when you need them.",
};

// Public: no sign-in. Plans, credits and prices all come from the API (GET /billing/plans).
export default function Page() {
  return (
    <div className="flex flex-1 flex-col">
      <header className="mx-auto flex w-full max-w-6xl items-center justify-between gap-4 px-4 py-4 sm:px-6">
        <Link href="/" className="text-lg font-semibold tracking-tight">
          JobFinder
        </Link>
        <nav aria-label="Account" className="flex items-center gap-2">
          <Link href="/login" className={buttonVariants({ variant: "ghost" })}>
            Sign in
          </Link>
          <Link href="/signup" className={buttonVariants({ variant: "outline" })}>
            Sign up
          </Link>
        </nav>
      </header>

      <main className="mx-auto flex w-full max-w-6xl flex-1 flex-col gap-8 px-4 pt-8 pb-16 sm:px-6">
        <div className="flex flex-col gap-3">
          <h1 className="text-3xl font-semibold tracking-tight sm:text-4xl">Pricing</h1>
          <p className="max-w-2xl text-lg text-pretty text-muted-foreground">
            Start with a free trial. When you need more, choose a monthly plan or add credits that never expire.
          </p>
        </div>
        <PricingPage />
      </main>

      <footer className="border-t">
        <div className="mx-auto flex max-w-6xl flex-col items-center gap-3 px-4 py-6 text-sm text-muted-foreground sm:flex-row sm:justify-between sm:px-6">
          <p>© {new Date().getFullYear()} JobFinder</p>
          <LegalLinks />
        </div>
      </footer>
    </div>
  );
}
