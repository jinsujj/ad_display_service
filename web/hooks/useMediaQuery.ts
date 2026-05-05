"use client";

import { useEffect, useState } from "react";

/**
 * SSR-safe media query hook. First render always returns `false` so server
 * and client agree; the actual value is set in `useEffect`. Callers that
 * need to *render* different DOM should prefer Tailwind `hidden md:block` /
 * `md:hidden` instead — this hook is for *behavioral* differences (e.g.
 * which component to mount, like Dialog vs Sheet).
 */
export function useMediaQuery(query: string): boolean {
  const [matches, setMatches] = useState(false);

  useEffect(() => {
    if (typeof window === "undefined") return;
    const media = window.matchMedia(query);
    setMatches(media.matches);
    const listener = (event: MediaQueryListEvent) => setMatches(event.matches);
    media.addEventListener("change", listener);
    return () => media.removeEventListener("change", listener);
  }, [query]);

  return matches;
}
