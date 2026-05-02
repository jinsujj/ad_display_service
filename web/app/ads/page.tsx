/**
 * Ads index page (`/ads`).
 *
 * AC 3, Sub-AC 3 — entry point for the schedule form.
 *
 * The backend does not (yet) expose a `GET /api/ads` listing endpoint —
 * only `PUT/PATCH /api/ads/{id}/schedule` is in scope for AC 3. Until a
 * sibling AC adds a list endpoint, this page is a deliberately minimal
 * "find an ad by id" shim:
 *
 *   - It accepts an ad id (UUID) in a small form,
 *   - On submit, navigates to `/ads/{id}` where the actual schedule editor
 *     ([AdScheduleForm]) lives,
 *   - Renders an inline note explaining where the id comes from in the
 *     hackathon flow (the advertiser receives it on ad creation; for the
 *     demo it's pasted from the create-ad response or the H2 console).
 *
 * Once a GET /api/ads endpoint exists, this page should be upgraded to a
 * Server Component that lists every ad owned by the calling advertiser
 * with a "Edit schedule" link per row — matching the pattern already used
 * by `/devices` and `/videos`.
 */

import { AdLookupForm } from "@/components/AdLookupForm";

export const dynamic = "force-dynamic";

export const metadata = {
  title: "Ads · AdSignage Admin",
};

export default function AdsIndexPage() {
  return (
    <section>
      <div className="page-header">
        <div>
          <h1>Ads</h1>
          <div className="subtitle">
            Find an ad by id and edit its daily playback schedule.
          </div>
        </div>
      </div>

      <div className="notice" role="note">
        <strong>Hackathon scope note.</strong> The backend currently exposes
        only <code>PUT/PATCH /api/ads/&#123;id&#125;/schedule</code> (no list
        endpoint yet). Paste the ad UUID below to jump to its schedule
        editor — the create/list endpoints land in a sibling AC.
      </div>

      <h2 className="section-heading">Open an ad</h2>
      <AdLookupForm />
    </section>
  );
}
