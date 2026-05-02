import Link from "next/link";

export default function HomePage() {
  return (
    <section>
      <div className="page-header">
        <div>
          <h1>AdSignage Admin</h1>
          <div className="subtitle">
            Restaurant fridge digital signage — operator console.
          </div>
        </div>
      </div>
      <p className="muted">Pick a section to get started:</p>
      <ul>
        <li>
          <Link href="/videos">Videos</Link> — review every uploaded MP4 ad
          and upload a new one (with progress indication).
        </li>
        <li>
          <Link href="/ads">Ads</Link> — open an ad by id and edit its
          daily playback schedule (start/end window + plays per day).
        </li>
        <li>
          <Link href="/devices">Devices</Link> — view all signage devices and
          their current restaurant mapping.
        </li>
      </ul>
    </section>
  );
}
