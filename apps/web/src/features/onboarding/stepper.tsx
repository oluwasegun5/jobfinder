import { cn } from "@/lib/utils";

export const ONBOARDING_STEPS = ["Upload your CV", "Review your profile", "Set your preferences"] as const;

/** Where the user is in onboarding; the current step is marked for assistive technology too. */
export function Stepper({ current }: { current: 0 | 1 | 2 }) {
  return (
    <nav aria-label="Onboarding progress">
      <ol className="flex flex-col gap-2 sm:flex-row sm:gap-6">
        {ONBOARDING_STEPS.map((label, index) => (
          <li
            key={label}
            aria-current={index === current ? "step" : undefined}
            className={cn(
              "flex items-center gap-2 text-sm",
              index === current ? "font-medium text-foreground" : "text-muted-foreground",
            )}
          >
            <span
              aria-hidden
              className={cn(
                "flex size-6 items-center justify-center rounded-full border text-xs",
                index < current && "bg-primary text-primary-foreground",
                index === current && "border-primary",
              )}
            >
              {index + 1}
            </span>
            {label}
            {index < current && <span className="sr-only">(done)</span>}
          </li>
        ))}
      </ol>
    </nav>
  );
}
