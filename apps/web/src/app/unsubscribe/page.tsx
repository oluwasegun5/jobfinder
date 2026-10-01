import type { Metadata } from "next";
import Link from "next/link";
import { Suspense } from "react";

import { Card, CardContent } from "@/components/ui/card";
import { Unsubscribe } from "@/features/notifications/unsubscribe";

export const metadata: Metadata = { title: "Unsubscribe", robots: { index: false } };

/** Public, outside both the signed-in and the guest-only groups: people open it from an email, signed in or not. */
export default function Page() {
  return (
    <main className="flex flex-1 flex-col items-center justify-center gap-6 px-4 py-12">
      <Link href="/" className="text-lg font-semibold tracking-tight">
        JobFinder
      </Link>
      <Card className="w-full max-w-sm">
        <CardContent>
          <Suspense>
            <Unsubscribe />
          </Suspense>
        </CardContent>
      </Card>
    </main>
  );
}
