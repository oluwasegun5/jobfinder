import type { Metadata } from "next";

import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { ApiStatus } from "@/features/health/api-status";

export const metadata: Metadata = { title: "Dashboard" };

export default function DashboardPage() {
  return (
    <div className="mx-auto flex max-w-4xl flex-col gap-6">
      <h1 className="text-2xl font-semibold tracking-tight">Dashboard</h1>
      <div className="grid gap-4 sm:grid-cols-2">
        <Card>
          <CardHeader>
            <CardTitle>Welcome</CardTitle>
            <CardDescription>
              Your matches, tailored documents and applications will show up here.
            </CardDescription>
          </CardHeader>
        </Card>
        <Card>
          <CardHeader>
            <CardTitle>System status</CardTitle>
            <CardDescription>Live health of the JobFinder API.</CardDescription>
          </CardHeader>
          <CardContent>
            <ApiStatus />
          </CardContent>
        </Card>
      </div>
    </div>
  );
}
