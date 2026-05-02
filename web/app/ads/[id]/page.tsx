/**
 * Ad detail / edit page (`/ads/[id]`).
 *
 * AC 3, Sub-AC 3:
 *   "Build Next.js admin schedule form UI with datetime pickers and play
 *    count input on ad detail/edit page."
 *
 * Implementation notes:
 *   - Server Component shell — renders the page chrome, the ad id banner,
 *     and a Client Component [AdScheduleForm] for the actual interactive
 *     form. No JS is required to render the shell, but the form itself
 *     hydrates into a controlled-input client component.
 *   - There is no GET /api/ads/{id} endpoint yet (out of Sub-AC 3 scope —
 *     only the PUT/PATCH /schedule verb exists), so the page does NOT
 *     attempt to pre-fetch the current schedule. The form starts empty
 *     and submits a complete replacement, matching the PUT semantics
 *     documented on `AdController.putSchedule`.
 *   - When the backend later exposes a single-ad read endpoint, this page
 *     should server-side-fetch it and pass the values via
 *     [AdScheduleForm.initialValues]; the form is already wired for that
 *     prop.
 *   - `dynamic = "force-dynamic"` so each visit reads live data when a GET
 *     endpoint is added later, instead of caching a stale snapshot.
 */

import Link from "next/link";

import { AdScheduleForm } from "@/components/AdScheduleForm";

export const dynamic = "force-dynamic";

export const metadata = {
  title: "Ad schedule · AdSignage Admin",
};

interface AdEditPageProps {
  params: { id: string };
}

export default function AdEditPage({ params }: AdEditPageProps) {
  const adId = params.id;

  return (
    <section>
      <div className="page-header">
        <div>
          <h1>Edit ad schedule</h1>
          <div className="subtitle">
            Update the daily playback window and target play count for this
            ad. Changes are saved immediately on submit and the next playlist
            refresh will pick them up.
          </div>
        </div>
        <Link href="/ads" className="btn">
          ← Back to ads
        </Link>
      </div>

      <h2 className="section-heading">Ad reference</h2>
      <div className="ad-id-banner">
        <span className="muted">Ad id</span>{" "}
        <code className="ad-id-banner__id">{adId}</code>
      </div>

      <h2 className="section-heading" style={{ marginTop: 24 }}>
        Schedule
      </h2>
      <AdScheduleForm adId={adId} />

      <p className="muted" style={{ marginTop: 24 }}>
        Submits to <code>PUT /api/ads/{adId}/schedule</code> on the Spring
        Boot backend. The endpoint is JWT-authenticated — store a token under{" "}
        <code>localStorage.adsignage_auth_token</code> (the login UI lands in
        a sibling AC). Server-side cross-field validation enforces{" "}
        <code>endTime &gt; startTime</code> and a daily count of{" "}
        <code>1..10000</code>.
      </p>
    </section>
  );
}
