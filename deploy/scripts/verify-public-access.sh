#!/usr/bin/env bash
# =============================================================================
# verify-public-access.sh
#
# End-to-end smoke test for stream.owl-dev.me. Verifies:
#   1. DNS resolves to the expected public IPv4 (110.8.21.243 by default).
#   2. HTTP :80  -> 301 redirect to HTTPS.
#   3. HTTPS :443 TLS handshake uses a cert whose SAN includes the hostname.
#   4. Spring Boot backend is reachable through nginx (`/actuator/health`).
#   5. Backend API endpoints respond (signup, devices list, restaurants list).
#   6. SSE endpoint flushes a `text/event-stream` headers block.
#   7. Range request against `/api/videos/<id>` returns `206 Partial Content`
#      (skipped if no video id is supplied).
#   8. Next.js admin web (`/admin` or `/`) responds with 2xx.
#   9. Next.js player page (`/player/<deviceId>`) responds with 2xx.
#
# Usage:
#   deploy/scripts/verify-public-access.sh                 # uses defaults
#   HOST=stream.owl-dev.me deploy/scripts/verify-public-access.sh
#   VIDEO_ID=abc123 DEVICE_ID=demo-1 deploy/scripts/verify-public-access.sh
#
# Exit code is the number of failed checks (0 = all green).
# =============================================================================
set -uo pipefail

HOST="${HOST:-stream.owl-dev.me}"
EXPECT_IP="${EXPECT_IP:-110.8.21.243}"
DEVICE_ID="${DEVICE_ID:-verify-device}"
VIDEO_ID="${VIDEO_ID:-}"
TIMEOUT="${TIMEOUT:-10}"

# Honor NO_COLOR (https://no-color.org) and dumb terminals so the script
# produces clean output when piped to a file or CI log.
if [[ -n "${NO_COLOR:-}" ]] || [[ ! -t 1 ]] || [[ "${TERM:-}" == "dumb" ]]; then
  GREEN=""; RED=""; YELLOW=""; DIM=""; RESET=""
else
  GREEN=$'\033[0;32m'
  RED=$'\033[0;31m'
  YELLOW=$'\033[0;33m'
  DIM=$'\033[2m'
  RESET=$'\033[0m'
fi

PASS=0
FAIL=0
SKIP=0

ok()   { printf '  %s✓%s %s\n' "$GREEN" "$RESET" "$*"; PASS=$((PASS+1)); }
bad()  { printf '  %s✗%s %s\n' "$RED"   "$RESET" "$*"; FAIL=$((FAIL+1)); }
skip() { printf '  %s○%s %s\n' "$YELLOW" "$RESET" "$*"; SKIP=$((SKIP+1)); }
note() { printf '    %s%s%s\n' "$DIM" "$*" "$RESET"; }
section() { printf '\n%s\n' "== $* =="; }

# ---- 1. DNS ------------------------------------------------------------------
section "1. DNS resolution"
RESOLVED="$(dig +short "$HOST" A | head -n1)"
if [[ -z "$RESOLVED" ]]; then
  bad "$HOST has no A record"
elif [[ "$RESOLVED" == "$EXPECT_IP" ]]; then
  ok "$HOST -> $RESOLVED (matches EXPECT_IP)"
else
  bad "$HOST -> $RESOLVED (expected $EXPECT_IP — set EXPECT_IP=$RESOLVED to override)"
fi

# ---- 2. HTTP -> HTTPS redirect ----------------------------------------------
section "2. HTTP :80 -> HTTPS redirect"
read -r CODE LOC < <(curl -sS -o /dev/null --max-time "$TIMEOUT" \
  -w '%{http_code} %{redirect_url}\n' "http://$HOST/" 2>/dev/null)
CODE="${CODE:-000}"; LOC="${LOC:--}"
if [[ "$CODE" == "301" || "$CODE" == "302" ]] && [[ "$LOC" == https://* ]]; then
  ok "http://$HOST/ -> $CODE -> $LOC"
else
  bad "http://$HOST/ returned code=$CODE redirect=$LOC (want 301 -> https://…)"
fi

# ---- 3. TLS certificate SAN match -------------------------------------------
section "3. TLS certificate"
CERT_TXT="$(echo \
  | openssl s_client -servername "$HOST" -connect "$HOST:443" -verify_return_error 2>/dev/null \
  | openssl x509 -noout -subject -issuer -dates -ext subjectAltName 2>/dev/null)"
if [[ -z "$CERT_TXT" ]]; then
  bad "could not fetch TLS cert from $HOST:443"
else
  note "$(printf '%s\n' "$CERT_TXT" | sed 's/^/  /')"
  if printf '%s\n' "$CERT_TXT" | grep -q "DNS:$HOST"; then
    ok "cert SAN includes DNS:$HOST"
  else
    bad "cert SAN does NOT include DNS:$HOST (run deploy/scripts/provision-tls.sh on the server)"
  fi
fi

# ---- helper: GET expecting one of N status codes ----------------------------
# usage: check_get <label> <path> <regex-of-acceptable-codes>
check_get() {
  local label="$1" path="$2" want="$3"
  local code
  # -k: cert validity is checked separately in section 3; this lets us probe
  # backend/admin/player functionality even before the right cert is in place.
  code="$(curl -skS -o /dev/null --max-time "$TIMEOUT" \
    -w '%{http_code}' "https://$HOST$path" 2>/dev/null)"
  code="${code:-000}"
  if [[ "$code" =~ ^($want)$ ]]; then
    ok "$label -> $code"
  else
    bad "$label -> $code (want one of: $want)"
  fi
}

# ---- 4. Backend health ------------------------------------------------------
section "4. Spring Boot health"
check_get "GET /actuator/health" "/actuator/health" "200"

# ---- 5. Backend API surface --------------------------------------------------
section "5. Backend API endpoints"
# /api/auth/signup is POST-only; a GET should return 405 (Method Not Allowed)
# rather than 404. Anything in 2xx/4xx (except 404) means the route exists.
check_get "GET /api/auth/signup (expect 405)" "/api/auth/signup" "200|400|401|403|405"
check_get "GET /api/devices"                  "/api/devices"     "200|401|403"
check_get "GET /api/restaurants"              "/api/restaurants" "200|401|403"

# ---- 6. SSE -----------------------------------------------------------------
section "6. SSE stream (/api/sse/devices/$DEVICE_ID)"
SSE_HEADERS="$(curl -skS -N --max-time 5 -D - -o /dev/null \
  -H 'Accept: text/event-stream' \
  "https://$HOST/api/sse/devices/$DEVICE_ID" 2>/dev/null || true)"
SSE_CODE="$(printf '%s\n' "$SSE_HEADERS" | awk 'NR==1 {print $2}')"
if [[ "$SSE_CODE" == "200" ]] && \
   printf '%s\n' "$SSE_HEADERS" | grep -qi 'content-type: *text/event-stream'; then
  ok "SSE endpoint streams text/event-stream (HTTP $SSE_CODE)"
else
  bad "SSE endpoint not streaming (code=$SSE_CODE)"
  note "first response line: $(printf '%s\n' "$SSE_HEADERS" | head -n1)"
fi

# ---- 7. Range video stream --------------------------------------------------
section "7. HTTP Range video stream"
if [[ -z "$VIDEO_ID" ]]; then
  skip "VIDEO_ID not set — skipping (set VIDEO_ID=<id> to test /api/videos/<id>)"
else
  CODE="$(curl -skS -o /dev/null --max-time "$TIMEOUT" \
    -H 'Range: bytes=0-1023' \
    -w '%{http_code}' "https://$HOST/api/videos/$VIDEO_ID" 2>/dev/null)"
  CODE="${CODE:-000}"
  if [[ "$CODE" == "206" ]]; then
    ok "GET /api/videos/$VIDEO_ID Range:0-1023 -> 206 Partial Content"
  else
    bad "GET /api/videos/$VIDEO_ID Range:0-1023 -> $CODE (want 206)"
  fi
fi

# ---- 8. Next.js admin web ---------------------------------------------------
section "8. Next.js admin web"
ADMIN_BODY="$(curl -skS --max-time "$TIMEOUT" "https://$HOST/admin" 2>/dev/null || true)"
ADMIN_CODE="$(curl -skS -o /dev/null --max-time "$TIMEOUT" \
  -w '%{http_code}' "https://$HOST/admin" 2>/dev/null)"
ADMIN_CODE="${ADMIN_CODE:-000}"
if [[ "$ADMIN_CODE" == "200" ]] && [[ "$ADMIN_BODY" == *"<html"* || "$ADMIN_BODY" == *"<!DOCTYPE"* ]]; then
  ok "GET /admin -> 200 (HTML payload)"
else
  bad "GET /admin -> $ADMIN_CODE (want 200 + HTML)"
fi

# ---- 9. Next.js player page -------------------------------------------------
section "9. Next.js player page"
PLAYER_BODY="$(curl -skS --max-time "$TIMEOUT" "https://$HOST/player/$DEVICE_ID" 2>/dev/null || true)"
PLAYER_CODE="$(curl -skS -o /dev/null --max-time "$TIMEOUT" \
  -w '%{http_code}' "https://$HOST/player/$DEVICE_ID" 2>/dev/null)"
PLAYER_CODE="${PLAYER_CODE:-000}"
if [[ "$PLAYER_CODE" == "200" ]] && [[ "$PLAYER_BODY" == *"<html"* || "$PLAYER_BODY" == *"<!DOCTYPE"* ]]; then
  ok "GET /player/$DEVICE_ID -> 200 (HTML payload)"
else
  bad "GET /player/$DEVICE_ID -> $PLAYER_CODE (want 200 + HTML)"
fi

# ---- summary ----------------------------------------------------------------
printf '\n=================================\n'
printf 'pass=%s  fail=%s  skip=%s\n' "$PASS" "$FAIL" "$SKIP"
printf '=================================\n'
exit "$FAIL"
