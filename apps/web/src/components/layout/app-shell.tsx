"use client";

import { Bell, Bookmark, BookmarkCheck, Briefcase, Coins, CreditCard, DatabaseZap, KanbanSquare, LayoutDashboard, Menu, MessagesSquare, ShieldCheck, Sparkles, UserRound, X } from "lucide-react";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { useEffect, useRef, useState, type ComponentType, type ReactNode } from "react";

import { Wordmark } from "@/components/layout/site-shell";
import { Button } from "@/components/ui/button";
import { useAuth } from "@/features/auth/auth-provider";
import { UserMenu } from "@/features/auth/user-menu";
import { ApiStatus } from "@/features/health/api-status";
import { cn } from "@/lib/utils";

type NavItem = {
  label: string;
  icon: ComponentType<{ className?: string }>;
  href: string;
};

/** Groups read top to bottom as the job search does: find, track, then the account. A divider separates them. */
const navGroups: NavItem[][] = [
  [
    { label: "Dashboard", icon: LayoutDashboard, href: "/dashboard" },
    { label: "For you", icon: Sparkles, href: "/feed" },
    { label: "Jobs", icon: Briefcase, href: "/jobs" },
    { label: "Saved jobs", icon: Bookmark, href: "/saved-jobs" },
    { label: "Saved searches", icon: BookmarkCheck, href: "/saved-searches" },
  ],
  [
    { label: "Applications", icon: KanbanSquare, href: "/applications" },
    { label: "Interviews", icon: MessagesSquare, href: "/interviews" },
  ],
  [
    { label: "Profile", icon: UserRound, href: "/profile" },
    { label: "Billing", icon: CreditCard, href: "/billing" },
    { label: "Notifications", icon: Bell, href: "/settings/notifications" },
    { label: "Privacy and data", icon: ShieldCheck, href: "/settings/privacy" },
  ],
];

/** Shown to administrators only (core-api enforces the role; this just keeps the menu honest). */
const adminNavItems: NavItem[] = [
  { label: "Job sources", icon: DatabaseZap, href: "/admin/ingestion" },
  { label: "AI cost", icon: Coins, href: "/admin/billing" },
];

function NavLinks({ onNavigate }: { onNavigate?: () => void }) {
  const pathname = usePathname();
  const { user } = useAuth();
  const groups = user?.role === "ADMIN" ? [...navGroups, adminNavItems] : navGroups;

  return (
    <div className="flex flex-col gap-3">
      {groups.map((items, index) => (
        <ul key={items[0].href} className={cn("flex flex-col gap-1", index > 0 && "border-t pt-3")}>
          {items.map(({ label, icon: Icon, href }) => {
            const active = pathname === href || pathname.startsWith(`${href}/`);
            return (
              <li key={label}>
                <Link
                  href={href}
                  onClick={onNavigate}
                  aria-current={active ? "page" : undefined}
                  className={cn(
                    // 44px tall on phones, 36px from md where a pointer is likely.
                    "flex min-h-11 items-center gap-3 rounded-lg px-3 text-sm font-medium transition-colors duration-120 ease-standard md:min-h-9",
                    active
                      ? "bg-secondary text-secondary-foreground"
                      : "text-muted-foreground hover:bg-muted hover:text-foreground",
                  )}
                >
                  <Icon className={cn("size-4 shrink-0", active && "text-primary")} />
                  {label}
                </Link>
              </li>
            );
          })}
        </ul>
      ))}
    </div>
  );
}

export function AppShell({ children }: { children: ReactNode }) {
  const [mobileNavOpen, setMobileNavOpen] = useState(false);
  const toggleRef = useRef<HTMLButtonElement>(null);
  const drawerRef = useRef<HTMLElement>(null);

  // The drawer is a small dialog-like surface: focus moves into it, Escape closes it and gives focus back.
  useEffect(() => {
    if (!mobileNavOpen) return;
    drawerRef.current?.querySelector<HTMLElement>("a")?.focus();
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key !== "Escape") return;
      setMobileNavOpen(false);
      toggleRef.current?.focus();
    };
    document.addEventListener("keydown", onKeyDown);
    return () => document.removeEventListener("keydown", onKeyDown);
  }, [mobileNavOpen]);

  return (
    <div className="flex min-h-dvh flex-1">
      <a
        href="#main"
        className="sr-only focus:not-sr-only focus:fixed focus:top-2 focus:left-2 focus:z-50 focus:rounded-md focus:bg-card focus:px-3 focus:py-2 focus:shadow-lg"
      >
        Skip to content
      </a>

      <aside className="sticky top-0 hidden h-dvh w-60 shrink-0 flex-col gap-6 overflow-y-auto border-r bg-sidebar p-4 md:flex">
        <Wordmark className="px-1" />
        <nav aria-label="Main">
          <NavLinks />
        </nav>
        <div className="mt-auto flex flex-col gap-4">
          <UserMenu />
          <ApiStatus />
        </div>
      </aside>

      <div className="flex min-w-0 flex-1 flex-col">
        <header className="sticky top-0 z-30 flex h-14 items-center justify-between gap-3 border-b bg-background/95 px-4 backdrop-blur md:hidden">
          <Wordmark />
          <Button
            ref={toggleRef}
            variant="ghost"
            size="icon-lg"
            aria-label={mobileNavOpen ? "Close menu" : "Open menu"}
            aria-expanded={mobileNavOpen}
            aria-controls="mobile-nav"
            onClick={() => setMobileNavOpen((open) => !open)}
          >
            {mobileNavOpen ? <X /> : <Menu />}
          </Button>
        </header>
        {mobileNavOpen && (
          <div className="fixed inset-x-0 top-14 bottom-0 z-20 md:hidden">
            <div aria-hidden className="absolute inset-0 bg-foreground/40" onClick={() => setMobileNavOpen(false)} />
            <nav
              id="mobile-nav"
              ref={drawerRef}
              aria-label="Main"
              className="absolute inset-x-0 top-0 max-h-full overflow-y-auto border-b bg-card p-4 shadow-lg"
            >
              <NavLinks onNavigate={() => setMobileNavOpen(false)} />
              <div className="mt-4 flex flex-col gap-4 border-t pt-4">
                <UserMenu />
                <ApiStatus />
              </div>
            </nav>
          </div>
        )}

        <main id="main" tabIndex={-1} className="flex-1 p-4 outline-none sm:p-6 md:p-8">
          {children}
        </main>
      </div>
    </div>
  );
}
