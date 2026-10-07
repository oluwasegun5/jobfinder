import Link from "next/link";

import { LegalLinks } from "@/features/legal/legal-page";

export default function LegalLayout({ children }: LayoutProps<"/">) {
  return (
    <div className="flex flex-1 flex-col">
      <header className="mx-auto flex w-full max-w-3xl items-center justify-between px-4 py-4 sm:px-6">
        <Link href="/" className="text-lg font-semibold tracking-tight">
          JobFinder
        </Link>
        <Link href="/login" className="text-sm underline underline-offset-4">
          Sign in
        </Link>
      </header>
      <main className="flex-1">{children}</main>
      <footer className="px-4 py-6">
        <LegalLinks />
      </footer>
    </div>
  );
}
