"use client";

import { Briefcase, KanbanSquare, LayoutDashboard, Menu, UserRound, X } from "lucide-react";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { useState, type ComponentType, type ReactNode } from "react";

import { Button } from "@/components/ui/button";
import { UserMenu } from "@/features/auth/user-menu";
import { ApiStatus } from "@/features/health/api-status";
import { cn } from "@/lib/utils";

type NavItem = {
  label: string;
  icon: ComponentType<{ className?: string }>;
  /** Items without an href belong to later phases and render as disabled. */
  href?: string;
};

const navItems: NavItem[] = [
  { label: "Dashboard", icon: LayoutDashboard, href: "/dashboard" },
  { label: "Jobs", icon: Briefcase },
  { label: "Applications", icon: KanbanSquare },
  { label: "Profile", icon: UserRound, href: "/profile" },
];

function NavLinks({ onNavigate }: { onNavigate?: () => void }) {
  const pathname = usePathname();

  return (
    <ul className="flex flex-col gap-1">
      {navItems.map(({ label, icon: Icon, href }) => {
        const itemClass =
          "flex items-center gap-3 rounded-lg px-3 py-2 text-sm font-medium outline-none focus-visible:ring-3 focus-visible:ring-ring/50";
        if (!href) {
          return (
            <li key={label}>
              <span
                aria-disabled="true"
                className={cn(itemClass, "cursor-not-allowed text-muted-foreground/60")}
              >
                <Icon className="size-4" />
                {label}
                <span className="ml-auto text-xs">Soon</span>
              </span>
            </li>
          );
        }
        const active = pathname === href || pathname.startsWith(`${href}/`);
        return (
          <li key={label}>
            <Link
              href={href}
              onClick={onNavigate}
              aria-current={active ? "page" : undefined}
              className={cn(
                itemClass,
                active ? "bg-muted text-foreground" : "text-muted-foreground hover:bg-muted hover:text-foreground",
              )}
            >
              <Icon className="size-4" />
              {label}
            </Link>
          </li>
        );
      })}
    </ul>
  );
}

function Brand() {
  return (
    <Link
      href="/"
      className="rounded-md text-lg font-semibold tracking-tight outline-none focus-visible:ring-3 focus-visible:ring-ring/50"
    >
      JobFinder
    </Link>
  );
}

export function AppShell({ children }: { children: ReactNode }) {
  const [mobileNavOpen, setMobileNavOpen] = useState(false);

  return (
    <div className="flex min-h-dvh flex-1">
      <a
        href="#main"
        className="sr-only focus:not-sr-only focus:fixed focus:top-2 focus:left-2 focus:z-50 focus:rounded-md focus:bg-background focus:px-3 focus:py-2 focus:shadow"
      >
        Skip to content
      </a>

      <aside className="hidden w-60 shrink-0 flex-col gap-6 border-r bg-sidebar p-4 md:flex">
        <Brand />
        <nav aria-label="Main">
          <NavLinks />
        </nav>
        <div className="mt-auto flex flex-col gap-4">
          <UserMenu />
          <ApiStatus />
        </div>
      </aside>

      <div className="flex min-w-0 flex-1 flex-col">
        <header className="flex h-14 items-center gap-3 border-b px-4 md:hidden">
          <Button
            variant="ghost"
            size="icon"
            aria-label={mobileNavOpen ? "Close menu" : "Open menu"}
            aria-expanded={mobileNavOpen}
            aria-controls="mobile-nav"
            onClick={() => setMobileNavOpen((open) => !open)}
          >
            {mobileNavOpen ? <X /> : <Menu />}
          </Button>
          <Brand />
        </header>
        {mobileNavOpen && (
          <nav id="mobile-nav" aria-label="Main" className="border-b p-4 md:hidden">
            <NavLinks onNavigate={() => setMobileNavOpen(false)} />
            <div className="mt-4 flex flex-col gap-4">
              <UserMenu />
              <ApiStatus />
            </div>
          </nav>
        )}

        <main id="main" tabIndex={-1} className="flex-1 p-4 outline-none md:p-8">
          {children}
        </main>
      </div>
    </div>
  );
}
