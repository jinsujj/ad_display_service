import type { Metadata } from "next";
import type { ReactNode } from "react";
import "./globals.css";

export const metadata: Metadata = {
  title: "AdSignage Admin",
  description:
    "Restaurant fridge digital signage admin — manage advertisers, ads, schedules, devices, and restaurant mappings.",
};

export default function RootLayout({ children }: { children: ReactNode }) {
  return (
    <html lang="en">
      <body>
        <div className="app-shell">
          <header className="app-header">
            <a href="/" className="brand">
              AdSignage&nbsp;Admin
            </a>
            <nav className="primary-nav">
              <a href="/videos">Videos</a>
              <a href="/ads">Ads</a>
              <a href="/devices">Devices</a>
            </nav>
          </header>
          <main className="app-main">{children}</main>
        </div>
      </body>
    </html>
  );
}
