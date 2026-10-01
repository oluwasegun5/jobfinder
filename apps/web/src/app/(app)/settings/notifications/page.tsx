import type { Metadata } from "next";

import { NotificationSettings } from "@/features/notifications/notification-settings";

export const metadata: Metadata = { title: "Notifications" };

export default function Page() {
  return (
    <div className="mx-auto flex max-w-3xl flex-col gap-6">
      <h1 className="text-2xl font-semibold tracking-tight">Notifications</h1>
      <NotificationSettings />
    </div>
  );
}
