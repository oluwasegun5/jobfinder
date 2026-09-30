import { AppShell } from "@/components/layout/app-shell";
import { RequireAuth } from "@/features/auth/route-guards";

export default function AppLayout({ children }: LayoutProps<"/">) {
  return (
    <RequireAuth>
      <AppShell>{children}</AppShell>
    </RequireAuth>
  );
}
