"use client";

import Script from "next/script";
import { useEffect, useRef, useState } from "react";

import { api } from "@/lib/api";

import { problemMessage } from "./api-errors";
import { useAuth } from "./auth-provider";

type GoogleId = {
  initialize: (config: { client_id: string; callback: (r: { credential: string }) => void }) => void;
  renderButton: (el: HTMLElement, options: Record<string, unknown>) => void;
};
declare global {
  interface Window {
    google?: { accounts: { id: GoogleId } };
  }
}

const clientId = process.env.NEXT_PUBLIC_GOOGLE_CLIENT_ID;

/** Google Identity Services button. Renders nothing unless NEXT_PUBLIC_GOOGLE_CLIENT_ID is set. */
export function GoogleButton({
  onSignedIn,
  onError,
}: {
  onSignedIn: () => void;
  onError: (message: string) => void;
}) {
  const { signIn } = useAuth();
  const container = useRef<HTMLDivElement>(null);
  const [scriptReady, setScriptReady] = useState(
    () => typeof window !== "undefined" && Boolean(window.google?.accounts),
  );

  useEffect(() => {
    if (!clientId || !scriptReady || !container.current || !window.google) return;
    window.google.accounts.id.initialize({
      client_id: clientId,
      callback: async ({ credential }) => {
        const { data, error } = await api.POST("/auth/google", { body: { idToken: credential } });
        if (!data) return onError(problemMessage(error, "Google sign-in failed."));
        await signIn(data);
        onSignedIn();
      },
    });
    window.google.accounts.id.renderButton(container.current, {
      theme: "outline",
      size: "large",
      text: "continue_with",
      width: 320,
    });
  }, [scriptReady, signIn, onSignedIn, onError]);

  if (!clientId) return null;
  return (
    <>
      <Script
        src="https://accounts.google.com/gsi/client"
        strategy="lazyOnload"
        onReady={() => setScriptReady(true)}
      />
      <div ref={container} className="flex min-h-10 justify-center" />
    </>
  );
}
