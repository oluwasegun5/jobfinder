import Link from "next/link";
import { Suspense } from "react";

import { Card, CardContent } from "@/components/ui/card";
import { GuestOnly } from "@/features/auth/route-guards";

export default function AuthLayout({ children }: LayoutProps<"/">) {
  return (
    <main className="flex flex-1 flex-col items-center justify-center gap-6 px-4 py-12">
      <Link href="/" className="text-lg font-semibold tracking-tight">
        JobFinder
      </Link>
      <Card className="w-full max-w-sm">
        <CardContent>
          <GuestOnly>
            <Suspense>{children}</Suspense>
          </GuestOnly>
        </CardContent>
      </Card>
    </main>
  );
}
