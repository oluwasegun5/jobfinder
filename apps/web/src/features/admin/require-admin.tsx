"use client";

import Link from "next/link";
import type { ReactNode } from "react";

import { useAuth } from "@/features/auth/auth-provider";

/** True for a core-api answer that says "not an admin" (the role can change while a page is open). */
export function isForbidden(error: unknown): boolean {
  return (error as { status?: number } | null)?.status === 403;
}

export function NotAllowed() {
  return (
    <div role="alert" className="flex flex-col gap-2 rounded-lg border bg-muted/40 p-4">
      <h1 className="text-lg font-semibold">Not allowed</h1>
      <p className="text-sm text-muted-foreground">This page is for administrators.</p>
      <p>
        <Link href="/dashboard" className="text-sm underline">
          Back to the dashboard
        </Link>
      </p>
    </div>
  );
}

/**
 * Shows its children to administrators only; everyone else gets a "not allowed" message instead of the page.
 * core-api enforces the role on every admin endpoint, so this is a courtesy, not the gate. Goes inside
 * {@link RequireAuth} (the app layout), which is what makes sure there is a user at all.
 */
export function RequireAdmin({ children }: { children: ReactNode }) {
  const { user } = useAuth();
  return user?.role === "ADMIN" ? children : <NotAllowed />;
}
