import { Suspense } from "react";

import { ProfileTabs } from "@/features/profile/profile-tabs";

export default function ProfileLayout({ children }: LayoutProps<"/">) {
  return (
    <div className="mx-auto flex max-w-3xl flex-col gap-6">
      <h1 className="text-2xl font-semibold tracking-tight">Profile</h1>
      <ProfileTabs />
      <Suspense>{children}</Suspense>
    </div>
  );
}
