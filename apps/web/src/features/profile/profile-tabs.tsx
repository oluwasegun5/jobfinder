"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";

import { cn } from "@/lib/utils";

const TABS = [
  { label: "Details", href: "/profile" },
  { label: "Preferences", href: "/profile/preferences" },
  { label: "CVs", href: "/profile/resumes" },
] as const;

export function ProfileTabs() {
  const pathname = usePathname();

  return (
    <nav aria-label="Profile sections" className="flex gap-1 border-b">
      {TABS.map(({ label, href }) => {
        const active = pathname === href;
        return (
          <Link
            key={href}
            href={href}
            aria-current={active ? "page" : undefined}
            className={cn(
              "-mb-px rounded-t-md border-b-2 px-3 py-2 text-sm font-medium outline-none focus-visible:ring-3 focus-visible:ring-ring/50",
              active ? "border-primary text-foreground" : "border-transparent text-muted-foreground hover:text-foreground",
            )}
          >
            {label}
          </Link>
        );
      })}
    </nav>
  );
}
