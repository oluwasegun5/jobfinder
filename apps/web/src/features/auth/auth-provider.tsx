"use client";

import { useQueryClient } from "@tanstack/react-query";
import { useRouter } from "next/navigation";
import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from "react";

import { api } from "@/lib/api";
import {
  clearSession,
  refreshSession,
  setSession,
  subscribeToSession,
  type AuthTokens,
} from "@/lib/auth/session";

import type { components } from "@jobfinder/api-contract";

type User = components["schemas"]["MeResponse"];

type AuthState =
  | { status: "loading"; user: null }
  | { status: "unauthenticated"; user: null }
  | { status: "authenticated"; user: User };

type AuthContextValue = AuthState & {
  /** True after the user chose to sign out, so guards do not bounce them back with a ?next= return path. */
  signedOutByUser: boolean;
  /** Stores the tokens from a login / Google response and loads the user. */
  signIn: (tokens: AuthTokens) => Promise<void>;
  signOut: () => Promise<void>;
};

const AuthContext = createContext<AuthContextValue | null>(null);

/** Refresh this long before the access token expires. */
const REFRESH_MARGIN_MS = 60_000;

export function AuthProvider({ children }: { children: ReactNode }) {
  const router = useRouter();
  const queryClient = useQueryClient();
  const [state, setState] = useState<AuthState>({ status: "loading", user: null });
  const [signedOutByUser, setSignedOutByUser] = useState(false);

  const loadUser = useCallback(async () => {
    const { data } = await api.GET("/auth/me");
    setState(data ? { status: "authenticated", user: data } : { status: "unauthenticated", user: null });
  }, []);

  // Silent refresh on first load: the httpOnly cookie is the only thing that survives a reload.
  useEffect(() => {
    let cancelled = false;
    (async () => {
      const token = await refreshSession();
      if (cancelled) return;
      if (token) await loadUser();
      else setState({ status: "unauthenticated", user: null });
    })();
    return () => {
      cancelled = true;
    };
  }, [loadUser]);

  // Keep the access token fresh, and drop to logged-out when a refresh is rejected.
  useEffect(() => {
    let timer: ReturnType<typeof setTimeout> | undefined;
    const unsubscribe = subscribeToSession((token, expiresIn) => {
      clearTimeout(timer);
      if (!token) {
        queryClient.clear();
        setState({ status: "unauthenticated", user: null });
        return;
      }
      if (expiresIn) {
        timer = setTimeout(() => void refreshSession(), Math.max(expiresIn * 1000 - REFRESH_MARGIN_MS, 5_000));
      }
    });
    return () => {
      clearTimeout(timer);
      unsubscribe();
    };
  }, [queryClient]);

  const signIn = useCallback(
    async (tokens: AuthTokens) => {
      setSignedOutByUser(false);
      setSession(tokens);
      await loadUser();
    },
    [loadUser],
  );

  const signOut = useCallback(async () => {
    setSignedOutByUser(true);
    try {
      await api.POST("/auth/logout");
    } finally {
      clearSession();
      router.replace("/login");
    }
  }, [router]);

  const value = useMemo<AuthContextValue>(() => ({ ...state, signedOutByUser, signIn, signOut }),
    [state, signedOutByUser, signIn, signOut],);
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  const context = useContext(AuthContext);
  if (!context) throw new Error("useAuth must be used inside <AuthProvider>");
  return context;
}
