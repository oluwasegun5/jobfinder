import Link from "next/link";
import type { ReactNode } from "react";

import { PLACEHOLDERS, type PlaceholderKey } from "./placeholders";

/** A value that must be filled in before launch, highlighted so nobody misses it. */
export function Fill({ of }: { of: PlaceholderKey }) {
  return (
    <mark className="rounded bg-yellow-200 px-1 text-foreground dark:bg-yellow-900" data-placeholder={of}>
      {PLACEHOLDERS[of]}
    </mark>
  );
}

export function TemplateNotice() {
  return (
    <div role="note" className="rounded-lg border border-yellow-500 bg-yellow-50 p-4 text-sm text-foreground dark:bg-yellow-950">
      <p className="font-semibold">Template: not legal advice</p>
      <p className="mt-1">
        This page is a starting template. It describes what the software does, but it has not been reviewed by a
        lawyer and must not be published until a qualified lawyer has reviewed it and every highlighted placeholder
        has been replaced.
      </p>
    </div>
  );
}

export function LegalPage({ title, children }: { title: string; children: ReactNode }) {
  return (
    <article className="mx-auto flex w-full max-w-3xl flex-col gap-6 px-4 py-10 sm:px-6">
      <h1 className="text-3xl font-semibold tracking-tight">{title}</h1>
      <TemplateNotice />
      <p className="text-sm text-muted-foreground">
        Effective: <Fill of="effectiveDate" />
      </p>
      <div className="flex flex-col gap-6 [&_h2]:text-xl [&_h2]:font-semibold [&_h2]:tracking-tight [&_li]:ml-5 [&_li]:list-disc [&_p]:leading-relaxed [&_section]:flex [&_section]:flex-col [&_section]:gap-3 [&_td]:border [&_td]:p-2 [&_th]:border [&_th]:p-2 [&_th]:text-left [&_table]:w-full [&_table]:border-collapse [&_table]:text-sm">
        {children}
      </div>
    </article>
  );
}

export function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section>
      <h2>{title}</h2>
      {children}
    </section>
  );
}

const links = [
  { href: "/privacy", label: "Privacy policy" },
  { href: "/terms", label: "Terms of service" },
  { href: "/cookies", label: "Cookie notice" },
  { href: "/subprocessors", label: "Subprocessors" },
];

export function LegalLinks({ className }: { className?: string }) {
  return (
    <nav aria-label="Legal" className={className}>
      <ul className="flex flex-wrap justify-center gap-x-4 gap-y-1 text-xs text-muted-foreground">
        {links.map((link) => (
          <li key={link.href}>
            <Link href={link.href} className="underline-offset-4 hover:underline focus-visible:underline">
              {link.label}
            </Link>
          </li>
        ))}
      </ul>
    </nav>
  );
}
