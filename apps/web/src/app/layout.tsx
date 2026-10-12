import type { Metadata, Viewport } from "next";
import { connection } from "next/server";
import { Bricolage_Grotesque } from "next/font/google";

import { Providers } from "./providers";
import "./globals.css";

// One self-hosted variable font (ADR 0043). next/font downloads it at build time and serves it from our origin.
const brand = Bricolage_Grotesque({
  variable: "--font-brand",
  subsets: ["latin"],
  axes: ["opsz"],
  display: "swap",
});

export const metadata: Metadata = {
  title: {
    default: "JobFinder",
    template: "%s · JobFinder",
  },
  description:
    "Find jobs from many sources, see how well you match, tailor your CV and track every application.",
};

export const viewport: Viewport = {
  themeColor: [
    { media: "(prefers-color-scheme: light)", color: "#f6f8f7" },
    { media: "(prefers-color-scheme: dark)", color: "#0d1513" },
  ],
};

// Every page is rendered per request so Next can stamp the CSP nonce (src/proxy.ts) on its scripts.
export default async function RootLayout({ children }: LayoutProps<"/">) {
  await connection();
  return (
    <html lang="en" className={`${brand.variable} h-full antialiased`}>
      <body className="flex min-h-full flex-col">
        <Providers>{children}</Providers>
      </body>
    </html>
  );
}
