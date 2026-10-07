import { FileText, KanbanSquare, Search, Sparkles, type LucideIcon } from "lucide-react";
import Link from "next/link";

import { buttonVariants } from "@/components/ui/button";
import { Card, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { LegalLinks } from "@/features/legal/legal-page";
import { cn } from "@/lib/utils";

const features: { title: string; description: string; icon: LucideIcon }[] = [
  {
    title: "Every job in one place",
    description: "Openings pulled from company career pages and job boards, de-duplicated and kept fresh.",
    icon: Search,
  },
  {
    title: "Matches that explain themselves",
    description: "A 0–100 score for each job, with the strengths and gaps behind it.",
    icon: Sparkles,
  },
  {
    title: "A CV for every application",
    description: "Tailored resumes and cover letters built from your real experience — you approve every change.",
    icon: FileText,
  },
  {
    title: "Track it all",
    description: "From saved to offer on one board, with reminders to follow up.",
    icon: KanbanSquare,
  },
];

export default function LandingPage() {
  return (
    <div className="flex flex-1 flex-col">
      <header className="mx-auto flex w-full max-w-6xl items-center justify-between gap-4 px-4 py-4 sm:px-6">
        <span className="text-lg font-semibold tracking-tight">JobFinder</span>
        <nav aria-label="Account" className="flex items-center gap-2">
          <Link href="/login" className={buttonVariants({ variant: "ghost" })}>
            Sign in
          </Link>
          <Link href="/signup" className={buttonVariants({ variant: "outline" })}>
            Sign up
          </Link>
        </nav>
      </header>

      <main className="flex-1">
        <section className="mx-auto flex max-w-3xl flex-col items-center gap-6 px-4 py-16 text-center sm:px-6 sm:py-24">
          <h1 className="text-4xl font-semibold tracking-tight text-balance sm:text-5xl">
            Find the right job, and apply with a CV made for it.
          </h1>
          <p className="max-w-2xl text-lg text-pretty text-muted-foreground">
            JobFinder gathers openings from across the web, ranks them against your profile and
            helps you tailor every application — without inventing a word of your experience.
          </p>
          <Link href="/signup" className={cn(buttonVariants({ size: "lg" }), "px-5")}>
            Get started
          </Link>
        </section>

        <section aria-labelledby="features-heading" className="mx-auto max-w-6xl px-4 pb-24 sm:px-6">
          <h2 id="features-heading" className="sr-only">
            Features
          </h2>
          <ul className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
            {features.map(({ title, description, icon: Icon }) => (
              <li key={title}>
                <Card className="h-full">
                  <CardHeader>
                    <Icon className="mb-2 size-5 text-muted-foreground" aria-hidden />
                    <CardTitle>{title}</CardTitle>
                    <CardDescription>{description}</CardDescription>
                  </CardHeader>
                </Card>
              </li>
            ))}
          </ul>
        </section>
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
