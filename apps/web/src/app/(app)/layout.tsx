import { AppShell } from "@/components/layout/app-shell";
import { RequireAuth } from "@/features/auth/route-guards";
import { ConsentGate } from "@/features/privacy/consent-gate";

export default function AppLayout({ children }: LayoutProps<"/">) {
  return (
    <RequireAuth>
      <AppShell>
        <ConsentGate>{children}</ConsentGate>
      </AppShell>
    </RequireAuth>
  );
}
