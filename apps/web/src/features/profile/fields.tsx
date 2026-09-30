import type { ComponentProps, ReactNode } from "react";

import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import { cn } from "@/lib/utils";

export { Field } from "@/features/auth/form-parts";

function Hint({ id, children }: { id: string; children?: ReactNode }) {
  if (!children) return null;
  return (
    <p id={`${id}-hint`} className="text-xs text-muted-foreground">
      {children}
    </p>
  );
}

export function TextAreaField({
  label,
  id,
  hint,
  ...props
}: ComponentProps<typeof Textarea> & { label: string; id: string; hint?: string }) {
  return (
    <div className="flex flex-col gap-1.5">
      <Label htmlFor={id}>{label}</Label>
      <Textarea id={id} name={id} aria-describedby={hint ? `${id}-hint` : undefined} {...props} />
      <Hint id={id}>{hint}</Hint>
    </div>
  );
}

export function SelectField({
  label,
  id,
  hint,
  children,
  ...props
}: ComponentProps<"select"> & { label: string; id: string; hint?: string }) {
  return (
    <div className="flex flex-col gap-1.5">
      <Label htmlFor={id}>{label}</Label>
      <select
        id={id}
        name={id}
        aria-describedby={hint ? `${id}-hint` : undefined}
        className={cn(
          "h-9 w-full min-w-0 rounded-lg border border-input bg-transparent px-2 text-base outline-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50 md:text-sm dark:bg-input/30",
        )}
        {...props}
      >
        {children}
      </select>
      <Hint id={id}>{hint}</Hint>
    </div>
  );
}

export function CheckboxField({
  label,
  id,
  hint,
  ...props
}: Omit<ComponentProps<"input">, "type"> & { label: string; id: string; hint?: string }) {
  return (
    <div className="flex flex-col gap-1">
      <label htmlFor={id} className="flex items-center gap-2 text-sm font-medium">
        <input
          id={id}
          name={id}
          type="checkbox"
          aria-describedby={hint ? `${id}-hint` : undefined}
          className="size-4 rounded border-input accent-primary focus-visible:ring-3 focus-visible:ring-ring/50"
          {...props}
        />
        {label}
      </label>
      <Hint id={id}>{hint}</Hint>
    </div>
  );
}

/** A titled group of inputs; the legend gives screen readers (and tests) a name for the group. */
export function Section({ title, children, className }: { title: string; children: ReactNode; className?: string }) {
  return (
    <fieldset className={cn("flex min-w-0 flex-col gap-4 rounded-xl border p-4", className)}>
      <legend className="px-1 text-base font-medium">{title}</legend>
      {children}
    </fieldset>
  );
}
