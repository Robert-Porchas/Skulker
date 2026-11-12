import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "Skulker · Strategy research",
  description:
    "Historical datasets, reproducible backtests, and transparent strategy comparisons in one research workspace.",
};

export default function RootLayout({
  children,
}: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="en">
      <body>{children}</body>
    </html>
  );
}
