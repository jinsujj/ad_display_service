# DNS — `stream.owl-dev.me`

The hackathon backend (Spring Boot), admin web (Next.js `/admin`), and player
page (Next.js `/player/{deviceId}`) are all served behind a single public
hostname:

```
stream.owl-dev.me  →  110.8.21.243  (public IPv4 of owl-SER8 / 192.168.0.24 LAN)
```

The parent zone `owl-dev.me` is hosted on **Gabia** (authoritative NS:
`ns.gabia.net`, `ns.gabia.co.kr`, `ns1.gabia.co.kr`).

## The record

A single **A record** is sufficient — no CNAME, no AAAA (server is IPv4 only).
This avoids the CNAME-at-apex pitfalls and keeps Let's Encrypt / SSE / Range
behaviour identical to a normal `<hostname> → <ip>` mapping.

| Type | Name     | Value           | TTL  | Notes                             |
| ---- | -------- | --------------- | ---- | --------------------------------- |
| A    | `stream` | `110.8.21.243`  | 300s | Created in the Gabia DNS console  |

> **Why A and not CNAME?** A CNAME at `stream.owl-dev.me` would force every
> resolver to do a second lookup and would also forbid coexisting records at
> that name. Since the server has a stable public IPv4 and we may later add
> TXT / MX records for the same hostname, an A record is the right call.

## Pre-flight checklist before requesting the cert

1. A record above is **live and propagated** (verified with `dig +short`).
2. **Inbound 80/tcp and 443/tcp** are open from the public internet to
   `110.8.21.243` (router port-forward → owl-SER8 LAN `192.168.0.24`).
3. Gabia "GSLB / 자동 페일오버" is **OFF** for this record (otherwise the
   resolver may receive an unexpected health-check IP).

## Verifying the record

```bash
$ dig +short stream.owl-dev.me A
110.8.21.243

$ dig +short stream.owl-dev.me AAAA
# (empty — IPv4-only by design)

$ curl -sI -o /dev/null -w '%{http_code}\n' http://stream.owl-dev.me/
301
```

A captured snapshot of the live record (taken 2026-05-02 from this dev
machine) is committed at [`evidence/2026-05-02-dig.txt`](evidence/2026-05-02-dig.txt)
for traceability.

## End-to-end verification

After the cert is provisioned (`deploy/scripts/provision-tls.sh`) and the
backend / Next.js app are deployed, run:

```bash
deploy/scripts/verify-public-access.sh
```

This script smoke-tests the four route classes that must be reachable for
the demo:

| Class                | Sample URL                                                     | Pass criteria                                          |
| -------------------- | -------------------------------------------------------------- | ------------------------------------------------------ |
| HTTP→HTTPS redirect  | `http://stream.owl-dev.me/`                                    | `301` to `https://…`                                   |
| Backend health       | `https://stream.owl-dev.me/actuator/health`                    | `200` + `{"status":"UP"}`                              |
| Backend API          | `https://stream.owl-dev.me/api/auth/signup` (POST)             | `2xx / 4xx` (anything *not* `404` / `502` / `5xx`)     |
| SSE endpoint         | `https://stream.owl-dev.me/api/sse/devices/<deviceId>`         | streaming `200` + `text/event-stream`                  |
| Range video stream   | `https://stream.owl-dev.me/api/videos/<id>` with `Range: 0-1k` | `206 Partial Content`                                  |
| Admin web            | `https://stream.owl-dev.me/admin` (or `/`)                     | `200` + HTML containing the admin shell                |
| Player page          | `https://stream.owl-dev.me/player/<deviceId>`                  | `200` + HTML containing the `<video>` mount point      |

## Current state (2026-05-02)

- [x] DNS A record live and resolving to `110.8.21.243`
- [x] Port 80 open — returns the expected `301` redirect
- [x] Port 443 open — TLS handshake completes
- [ ] TLS certificate for `stream.owl-dev.me` issued (currently nginx falls
      back to the `ai.owl-dev.me` cert because `provision-tls.sh` hasn't
      been run on owl-SER8 yet)
- [ ] Spring Boot backend deployed & responding on the public URL
- [ ] Next.js admin + player deployed & responding on the public URL

The DNS half of Sub-AC 3 is **complete and verified**. The remaining items
are deployment tasks tracked under their own sub-ACs.
