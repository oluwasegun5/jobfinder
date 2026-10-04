"use client";

import { Coins } from "lucide-react";
import Link from "next/link";

import { buttonVariants } from "@/components/ui/button";

/**
 * Shown wherever an AI action was refused with 402 (insufficient credits): says so in words and offers the way out,
 * the billing page. Announced to screen readers. Nothing was lost by the refusal, and the prompt says that.
 */
export function InsufficientCreditsPrompt({ message }: { message?: string }) {
  return (
    <div role="alert" className="flex flex-col gap-2 rounded-lg border border-amber-500/50 bg-amber-500/10 px-3 py-2 text-sm">
      <p className="flex items-start gap-2">
        <Coins className="mt-0.5 size-4 shrink-0" aria-hidden />
        <span>{message ?? "You are out of AI credits. Nothing was lost: what is already made is kept."}</span>
      </p>
      <div>
        <Link href="/billing" className={buttonVariants({ size: "sm" })}>
          Upgrade or add credits
        </Link>
      </div>
    </div>
  );
}
