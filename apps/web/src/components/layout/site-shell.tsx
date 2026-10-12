"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import type { ReactNode } from "react";

import { buttonVariants } from "@/components/ui/button";
import { LegalLinks } from "@/features/legal/legal-page";
import { cn } from "@/lib/utils";

/**
 * Public links in the marketing/auth header, before the account actions. Empty for now: G3 adds
 * `{ href: "/pricing", label: "Pricing" }` here.
 */
export const publicNavLinks: { href: string; label: string }[] = [];

/** The mark is a small score arc: the product's signature number, drawn instead of a logo file. */
function BrandMark({ className }: { className?: string }) {
  return (
    <svg viewBox="0 0 24 24" aria-hidden className={cn("size-6 shrink-0", className)}>
      <circle cx="12" cy="12" r="9" fill="none" strokeWidth="3.5" className="stroke-score-track" />
      <path
        d="M12 3a9 9 0 1 1-8.56 6.22"
        fill="none"
        strokeWidth="3.5"
        strokeLinecap="round"
        className="stroke-primary"
      />
      <circle cx="3.44" cy="9.22" r="2.4" className="fill-highlight" />
    </svg>
  );
}

export function Wordmark({ className }: { className?: string }) {
  return (
    <Link
      href="/"
      className={cn(
        "inline-flex items-center gap-2 rounded-md text-lg font-semibold tracking-[-0.02em] text-foreground",
        className,
      )}
    >
      <BrandMark />
      JobFinder
    </Link>
  );
}

export function SiteHeader() {
  const pathname = usePathname();
  const current = (href: string) => (pathname === href ? "page" : undefined);

  return (
    <header className="border-b bg-background/95">
      <a
        href="#main"
        className="sr-only focus:not-sr-only focus:fixed focus:top-2 focus:left-2 focus:z-50 focus:rounded-md focus:bg-card focus:px-3 focus:py-2 focus:shadow-lg"
      >
        Skip to content
      </a>
      <div className="mx-auto flex h-16 w-full max-w-6xl items-center gap-2 px-4 sm:gap-4 sm:px-6">
        <Wordmark className="mr-auto" />
        {publicNavLinks.length > 0 && (
          <nav aria-label="Main">
            <ul className="flex items-center gap-1">
              {publicNavLinks.map((link) => (
                <li key={link.href}>
                  <Link
                    href={link.href}
                    aria-current={current(link.href)}
                    className={cn(
                      buttonVariants({ variant: "ghost" }),
                      "px-2.5 text-muted-foreground hover:text-foreground aria-[current=page]:text-foreground",
                    )}
                  >
                    {link.label}
                  </Link>
                </li>
              ))}
            </ul>
          </nav>
        )}
        <nav aria-label="Account" className="flex items-center gap-1 sm:gap-2">
          <Link
            href="/login"
            aria-current={current("/login")}
            className={cn(buttonVariants({ variant: "ghost" }), "px-2.5")}
          >
            Sign in
          </Link>
          <Link href="/signup" aria-current={current("/signup")} className={buttonVariants()}>
            Sign up
          </Link>
        </nav>
      </div>
    </header>
  );
}

export function SiteFooter() {
  return (
    <footer className="border-t">
      <div className="mx-auto flex w-full max-w-6xl flex-col gap-4 px-4 py-8 text-sm text-muted-foreground sm:flex-row sm:items-center sm:justify-between sm:px-6">
        <p>© {new Date().getFullYear()} JobFinder</p>
        <LegalLinks className="[&_ul]:justify-start sm:[&_ul]:justify-end" />
      </div>
    </footer>
  );
}

/** Shared frame for the landing and auth pages: header, a main landmark, footer. */
export function SiteShell({ children, className }: { children: ReactNode; className?: string }) {
  return (
    <div className="flex min-h-dvh flex-1 flex-col">
      <SiteHeader />
      <main id="main" tabIndex={-1} className={cn("flex flex-1 flex-col outline-none", className)}>
        {children}
      </main>
      <SiteFooter />
    </div>
  );
}
