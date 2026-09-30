"use client";

import { LogOut } from "lucide-react";

import { Button } from "@/components/ui/button";

import { useAuth } from "./auth-provider";

export function UserMenu() {
  const { user, signOut } = useAuth();

  return (
    <div className="flex flex-col gap-2">
      {user && (
        <p className="truncate text-xs text-muted-foreground" title={user.email}>
          {user.email}
        </p>
      )}
      <Button variant="outline" size="sm" onClick={() => void signOut()}>
        <LogOut />
        Sign out
      </Button>
    </div>
  );
}
