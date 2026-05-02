# nginx — stream.owl-dev.me

Split-routing reverse proxy in front of:
  - Next.js admin web + `/player/{deviceId}` page (`localhost:3000`)
  - Spring Boot REST + video API (`localhost:8080`)

…on the Ubuntu host **owl-SER8 (192.168.0.24)**, with Let's Encrypt TLS and
HTTP→HTTPS redirect.

## Files in this folder

| File                                  | Purpose                                                       |
| ------------------------------------- | ------------------------------------------------------------- |
| `stream.owl-dev.me-bootstrap.conf`    | HTTP-only server block used **once** to obtain the first cert |
| `stream.owl-dev.me.conf`              | Full HTTPS reverse-proxy (Next.js + Spring Boot split)        |
| `../scripts/provision-tls.sh`         | Idempotent end-to-end TLS provisioning script (run as root)   |
| `../scripts/reload-nginx.sh`          | Re-sync repo config → `/etc/nginx/sites-available/`, validate via `nginx -t`, then reload (rolls back on validation failure) |

## What the full config does (AC 12)

| Path                | Upstream                | Behavior                                                          |
| ------------------- | ----------------------- | ----------------------------------------------------------------- |
| `http://…`          | (redirect)              | 301 → `https://…` (with `/.well-known/acme-challenge/` carve-out) |
| `/api/sse/*`        | `127.0.0.1:8080` (boot) | SSE-tuned: `proxy_buffering off`, 24h read timeout                |
| `/api/videos/*`     | `127.0.0.1:8080` (boot) | Range-aware passthrough for HTML5 `<video>` streaming             |
| `/videos/*`         | `127.0.0.1:8080` (boot) | Alias rewriting to `/api/videos/*` (Range-aware)                  |
| `/api/*`            | `127.0.0.1:8080` (boot) | REST API (catch-all under `/api/`)                                |
| `/actuator/*`       | `127.0.0.1:8080` (boot) | Health / metrics                                                  |
| `/*` (catch-all)    | `127.0.0.1:3000` (next) | Next.js admin pages + `/player/{deviceId}` + `_next` assets       |

Uploads up to **512 MB** (`client_max_body_size`) so advertisers can POST MP4s.

## One-shot install on owl-SER8 (recommended)

Pre-flight checklist:

1. DNS A record `stream.owl-dev.me` → public IPv4 of owl-SER8.
   See [`../dns/README.md`](../dns/README.md) for the exact record values
   and a verified `dig` snapshot.
2. Ports **80** and **443** reachable from the public internet.
3. Repo cloned onto the host (e.g. `/opt/adsignage`).

Then just run the provisioning script:

```bash
sudo LETSENCRYPT_EMAIL=admin@owl-dev.me \
     bash deploy/scripts/provision-tls.sh
```

What the script does:

1. `apt-get install -y nginx certbot python3-certbot-nginx`
2. Installs `stream.owl-dev.me-bootstrap.conf` (HTTP-only, ACME-friendly) and
   reloads nginx — this avoids the chicken-and-egg problem where the full
   config references cert files that do not yet exist.
3. Runs `certbot certonly --webroot -w /var/www/certbot -d stream.owl-dev.me`
   to obtain the cert non-interactively.
4. Swaps in the full HTTPS reverse-proxy config and reloads nginx.
5. Enables `certbot.timer` for automatic renewal and installs a deploy hook
   (`/etc/letsencrypt/renewal-hooks/deploy/reload-nginx.sh`) so nginx reloads
   after every renewal.
6. Runs `certbot renew --dry-run` to prove the renewal pipeline works.

The script is **idempotent** — re-running detects an existing cert and only
re-syncs the nginx config and renewal hook.

## Routine config edits — `reload-nginx.sh`

After the first-time TLS provisioning is done, use this for ANY subsequent
edit to `stream.owl-dev.me.conf`:

```bash
sudo bash deploy/scripts/reload-nginx.sh                # full HTTPS config
sudo MODE=bootstrap bash deploy/scripts/reload-nginx.sh # HTTP-only bootstrap
sudo VALIDATE_ONLY=1 bash deploy/scripts/reload-nginx.sh # nginx -t only, no reload
```

What it guarantees:

1. Backs up the current `/etc/nginx/sites-available/stream.owl-dev.me.conf`.
2. Stages the candidate from this repo + ensures the `sites-enabled` symlink.
3. Runs `nginx -t`. **If validation fails the previous file is restored and
   nginx is NOT reloaded** (script exits non-zero).
4. On validation success, runs `systemctl reload nginx` (falling back to
   `restart` if reload fails) and re-runs `nginx -t` to confirm the live
   state is sane.

It is idempotent — when the on-disk file already matches the repo it is a
no-op (still validates + reloads to converge state).

## Manual install (fallback)

```bash
# 1. Bootstrap config so port 80 answers ACME challenges
sudo cp deploy/nginx/stream.owl-dev.me-bootstrap.conf \
        /etc/nginx/sites-available/stream.owl-dev.me.conf
sudo ln -sf /etc/nginx/sites-available/stream.owl-dev.me.conf \
            /etc/nginx/sites-enabled/stream.owl-dev.me.conf
sudo rm -f /etc/nginx/sites-enabled/default
sudo mkdir -p /var/www/certbot
sudo nginx -t && sudo systemctl reload nginx

# 2. Provision the cert
sudo apt-get install -y certbot python3-certbot-nginx
sudo certbot certonly --webroot -w /var/www/certbot \
     -d stream.owl-dev.me \
     --non-interactive --agree-tos -m admin@owl-dev.me

# 3. Swap to the full HTTPS reverse-proxy config
sudo cp deploy/nginx/stream.owl-dev.me.conf \
        /etc/nginx/sites-available/stream.owl-dev.me.conf
sudo nginx -t && sudo systemctl reload nginx

# 4. Confirm auto-renewal
sudo systemctl enable --now certbot.timer
sudo certbot renew --dry-run
```

## Smoke tests

The full end-to-end verification (DNS, redirect, cert SAN, health, REST
routes, SSE, Range video, admin, player) is consolidated in:

```bash
deploy/scripts/verify-public-access.sh
# Optional overrides:
HOST=stream.owl-dev.me \
EXPECT_IP=110.8.21.243 \
DEVICE_ID=demo-1 VIDEO_ID=abc123 \
  deploy/scripts/verify-public-access.sh
# Exit code = number of failed checks (0 = all green).
```

Manual one-liners for ad-hoc debugging:

```bash
# Redirect works
curl -I  http://stream.owl-dev.me/                       # expect 301

# Next.js admin landing reachable through HTTPS
curl -I  https://stream.owl-dev.me/                      # expect 200 (Next.js)

# Spring Boot health reachable through HTTPS (split routing)
curl -I  https://stream.owl-dev.me/actuator/health       # expect 200 (Spring Boot)

# Range request passes through (replace <id> with a real video id)
curl -I -H 'Range: bytes=0-1023' \
     https://stream.owl-dev.me/api/videos/<id>           # expect 206 Partial Content
curl -I -H 'Range: bytes=0-1023' \
     https://stream.owl-dev.me/videos/<id>               # expect 206 (alias)

# SSE stream stays open and flushes (replace <deviceId>)
curl -N https://stream.owl-dev.me/api/sse/devices/<deviceId>

# Player page served by Next.js
curl -I  https://stream.owl-dev.me/player/demo-1         # expect 200 (Next.js)
```

## Notes

- The `map $http_upgrade $connection_upgrade` block lives at the top of the
  full config. If another server block already defines this map globally,
  delete it from this file to avoid a "duplicate map" error from `nginx -t`.
- `proxy_buffering off` on `/api/sse/` and `/api/videos/` is intentional —
  re-enabling it will break SSE delivery and stall video seeks.
- Certbot's auto-renewal is handled by the `certbot.timer` systemd unit
  (default cadence: twice daily, only renews when <30 days remaining).
