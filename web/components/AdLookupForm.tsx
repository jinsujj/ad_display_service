"use client";

/**
 * Ad-id lookup form (AC 3, Sub-AC 3 — companion to [AdScheduleForm]).
 *
 * Until the backend exposes a `GET /api/ads` listing, this is the entry
 * point that takes the operator from the `/ads` index to an ad-specific
 * schedule editor at `/ads/{id}`. It is intentionally minimal:
 *
 *   - one text input for the ad UUID,
 *   - synchronous trim + non-empty + UUID-shape validation so an obviously
 *     bad id doesn't 404 on the next page,
 *   - on submit, `router.push("/ads/{trimmed-id}")` — the destination page
 *     hosts the actual form.
 *
 * Why a Client Component:
 *   We need `useRouter` to navigate after validation, and a controlled
 *   input for the trim/validate UX. The wrapper page (`app/ads/page.tsx`)
 *   stays a Server Component.
 */

import { useState } from "react";
import { useRouter } from "next/navigation";

/**
 * Loose UUID-ish guard. We do not require a strict v4 because the server
 * also accepts any 36-char string as the ad id (UUIDs are produced by
 * `UUID.randomUUID()` on the server but the column is just `varchar(36)`).
 * The check is purely a UX layer to catch obvious paste-os.
 */
const ADID_REGEX = /^[0-9a-fA-F-]{8,40}$/;

export function AdLookupForm() {
  const router = useRouter();
  const [adId, setAdId] = useState<string>("");
  const [error, setError] = useState<string | null>(null);

  function handleSubmit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const trimmed = adId.trim();
    if (!trimmed) {
      setError("Ad id is required.");
      return;
    }
    if (!ADID_REGEX.test(trimmed)) {
      setError(
        "That doesn't look like a UUID. Paste the ad id from the create-ad response.",
      );
      return;
    }
    setError(null);
    router.push(`/ads/${encodeURIComponent(trimmed)}`);
  }

  return (
    <form className="ad-lookup-form" onSubmit={handleSubmit} noValidate>
      <label htmlFor="ad-lookup-id" className="schedule-form__label">
        Ad id (UUID)
      </label>
      <input
        id="ad-lookup-id"
        name="adId"
        type="text"
        value={adId}
        onChange={(e) => {
          setAdId(e.target.value);
          if (error) setError(null);
        }}
        placeholder="e.g. 7c4f3e92-4d8e-4d18-bc0e-3b2f1a0e5e21"
        autoComplete="off"
        spellCheck={false}
        className="schedule-form__input"
        aria-invalid={Boolean(error) || undefined}
        aria-describedby={error ? "ad-lookup-id-err" : undefined}
      />
      {error && (
        <div id="ad-lookup-id-err" className="schedule-form__field-error" role="alert">
          {error}
        </div>
      )}
      <div className="toolbar" style={{ marginTop: 12 }}>
        <button type="submit" className="btn">
          Open schedule editor
        </button>
      </div>
    </form>
  );
}

export default AdLookupForm;
