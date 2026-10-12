import * as React from "react"

import { cn } from "@/lib/utils"

// 44px tall, 16px text on phones (no zoom on focus), 3:1 border: docs/adr/0043-design-system.md ("Inputs").
function Input({ className, type, ...props }: React.ComponentProps<"input">) {
  return (
    <input
      type={type}
      data-slot="input"
      className={cn(
        "h-11 w-full min-w-0 rounded-lg border border-input bg-card px-3 py-1 text-base text-foreground shadow-xs transition-[border-color,box-shadow] duration-120 ease-standard outline-none placeholder:text-muted-foreground focus-visible:border-ring focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-ring disabled:pointer-events-none disabled:cursor-not-allowed disabled:opacity-50 aria-invalid:border-destructive aria-invalid:outline-destructive md:h-10 md:text-sm",
        className
      )}
      {...props}
    />
  )
}

export { Input }
