import type { Config } from "tailwindcss";
import tailwindcssAnimate from "tailwindcss-animate";
import forms from "@tailwindcss/forms";

const config: Config = {
  darkMode: "class",
  content: [
    "./app/**/*.{ts,tsx}",
    "./components/**/*.{ts,tsx}",
    "./hooks/**/*.{ts,tsx}",
    "./lib/**/*.{ts,tsx}",
  ],
  theme: {
    container: {
      center: true,
      padding: "1rem",
      screens: {
        "2xl": "1180px",
      },
    },
    extend: {
      colors: {
        // shadcn semantic colors — prefixed --sb-* so we don't collide with
        // legacy.css variables (--border, --muted, --accent are hex over there).
        border: "hsl(var(--sb-border))",
        input: "hsl(var(--sb-input))",
        ring: "hsl(var(--sb-ring))",
        background: "hsl(var(--sb-background))",
        foreground: "hsl(var(--sb-foreground))",
        primary: {
          DEFAULT: "hsl(var(--sb-primary))",
          foreground: "hsl(var(--sb-primary-foreground))",
        },
        secondary: {
          DEFAULT: "hsl(var(--sb-secondary))",
          foreground: "hsl(var(--sb-secondary-foreground))",
        },
        destructive: {
          DEFAULT: "hsl(var(--sb-destructive))",
          foreground: "hsl(var(--sb-destructive-foreground))",
        },
        muted: {
          DEFAULT: "hsl(var(--sb-muted))",
          foreground: "hsl(var(--sb-muted-foreground))",
        },
        accent: {
          DEFAULT: "hsl(var(--sb-accent))",
          foreground: "hsl(var(--sb-accent-foreground))",
        },
        popover: {
          DEFAULT: "hsl(var(--sb-popover))",
          foreground: "hsl(var(--sb-popover-foreground))",
        },
        card: {
          DEFAULT: "hsl(var(--sb-card))",
          foreground: "hsl(var(--sb-card-foreground))",
        },
        // AdSignage direct aliases — read raw hex tokens from legacy.css.
        "ad-bg": "var(--bg)",
        "ad-bg-elev": "var(--bg-elev)",
        "ad-bg-elev-2": "var(--bg-elev-2)",
        "ad-border": "var(--border)",
        "ad-border-soft": "var(--border-soft)",
        "ad-fg": "var(--fg)",
        "ad-fg-dim": "var(--fg-dim)",
        "ad-muted": "var(--muted)",
        "ad-accent": "var(--accent)",
        "ad-accent-hi": "var(--accent-hi)",
        "ad-ok": "var(--ok)",
        "ad-warn": "var(--warn)",
        "ad-err": "var(--err)",
      },
      borderRadius: {
        lg: "var(--sb-radius)",
        md: "calc(var(--sb-radius) - 2px)",
        sm: "calc(var(--sb-radius) - 4px)",
      },
      fontFamily: {
        sans: [
          "Pretendard Variable",
          "Pretendard",
          "-apple-system",
          "BlinkMacSystemFont",
          "Apple SD Gothic Neo",
          "Noto Sans KR",
          "Helvetica Neue",
          "Arial",
          "sans-serif",
        ],
        mono: [
          "JetBrains Mono",
          "ui-monospace",
          "SFMono-Regular",
          "SF Mono",
          "Menlo",
          "Consolas",
          "Liberation Mono",
          "monospace",
        ],
      },
      maxWidth: {
        content: "1180px",
        narrow: "720px",
      },
      keyframes: {
        "accordion-down": {
          from: { height: "0" },
          to: { height: "var(--radix-accordion-content-height)" },
        },
        "accordion-up": {
          from: { height: "var(--radix-accordion-content-height)" },
          to: { height: "0" },
        },
        "pill-pulse": {
          "0%, 100%": { opacity: "0.85" },
          "50%": { opacity: "0.35" },
        },
      },
      animation: {
        "accordion-down": "accordion-down 0.2s ease-out",
        "accordion-up": "accordion-up 0.2s ease-out",
        "pill-pulse": "pill-pulse 2.4s ease-in-out infinite",
      },
    },
  },
  plugins: [tailwindcssAnimate, forms({ strategy: "class" })],
};

export default config;
