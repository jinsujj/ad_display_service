/** @type {import('next').NextConfig} */
const nextConfig = {
  reactStrictMode: true,
  // The admin web is served behind the same nginx that fronts Spring Boot
  // (stream.owl-dev.me). We keep `output: 'standalone'` off for the hackathon
  // PoC — `next start -p 3000` is good enough to demo behind the reverse
  // proxy. If we move to static export later, switch to `output: 'export'`.
  experimental: {
    // App Router is the default in Next 14; nothing to opt into here.
  },
};

export default nextConfig;
