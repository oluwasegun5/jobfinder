"use client";

import { usePathname, useRouter } from "next/navigation";
import { useEffect, type ReactNode } from "react";

import { useAuth } from "./auth-provider";

function Loading() {
  return (
    <div role="status" className="flex flex-1 items-center justify-center p-8 text-sm text-muted-foreground">
      Loading…
    </div>
  );
}

/** Wraps signed-in pages: unauthenticated visitors are sent to /login and returned afterwards. */
export function RequireAuth({ children }: { children: ReactNode }) {
  const { status, signedOutByUser } = useAuth();
  const router = useRouter();
  const pathname = usePathname();

  useEffect(() => {
    if (status !== "unauthenticated") return;
    router.replace(signedOutByUser ? "/login" : `/login?next=${encodeURIComponent(pathname)}`);
  }, [status, signedOutByUser, router, pathname]);

  return status === "authenticated" ? children : <Loading />;
}

/** Wraps login/signup pages: signed-in users have nothing to do there. */
export function GuestOnly({ children }: { children: ReactNode }) {
  const { status } = useAuth();
  const router = useRouter();

  useEffect(() => {
    if (status === "authenticated") router.replace("/dashboard");
  }, [status, router]);

  return status === "unauthenticated" ? children : <Loading />;
}
