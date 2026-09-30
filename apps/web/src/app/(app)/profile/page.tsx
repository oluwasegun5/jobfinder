import type { Metadata } from "next";

import { ProfileDetails } from "@/features/profile/profile-details";

export const metadata: Metadata = { title: "Profile" };

export default function Page() {
  return <ProfileDetails />;
}
