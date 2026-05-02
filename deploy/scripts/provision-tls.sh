#!/usr/bin/env bash
# =============================================================================
# provision-tls.sh
#
# Idempotent provisioning of a Let's Encrypt TLS certificate for
# stream.owl-dev.me on the Ubuntu host (owl-SER8 / 192.168.0.24) and switch
# nginx from the bootstrap HTTP-only config to the full HTTPS reverse-proxy
# config.
#
# Run as root (or via sudo) on the target host:
#   sudo bash deploy/scripts/provision-tls.sh
#
# Re-running is safe: certbot will detect the existing cert and skip issuance,
# and the nginx config swap is a plain `cp`.
#
# Required upfront:
#   - DNS A record stream.owl-dev.me -> public IPv4 of this host
#   - ports 80 and 443 reachable from the public internet
# =============================================================================
set -euo pipefail

DOMAIN="stream.owl-dev.me"
EMAIL="${LETSENCRYPT_EMAIL:-admin@owl-dev.me}"
WEBROOT="/var/www/certbot"
NGINX_AVAILABLE="/etc/nginx/sites-available/${DOMAIN}.conf"
NGINX_ENABLED="/etc/nginx/sites-enabled/${DOMAIN}.conf"

# Resolve the repo root regardless of where the script is invoked from
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
BOOTSTRAP_CONF="${REPO_ROOT}/deploy/nginx/${DOMAIN}-bootstrap.conf"
FULL_CONF="${REPO_ROOT}/deploy/nginx/${DOMAIN}.conf"

log()  { printf '\033[1;36m[provision-tls]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[provision-tls]\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[1;31m[provision-tls]\033[0m %s\n' "$*" >&2; exit 1; }

[[ $EUID -eq 0 ]] || die "run as root (sudo bash $0)"
[[ -f "${BOOTSTRAP_CONF}" ]] || die "missing ${BOOTSTRAP_CONF}"
[[ -f "${FULL_CONF}"      ]] || die "missing ${FULL_CONF}"

# ---- 1. install certbot + nginx ---------------------------------------------
log "installing nginx, certbot, certbot-nginx..."
export DEBIAN_FRONTEND=noninteractive
apt-get update -y
apt-get install -y nginx certbot python3-certbot-nginx

# ---- 2. ensure webroot exists -----------------------------------------------
log "ensuring webroot ${WEBROOT}..."
mkdir -p "${WEBROOT}/.well-known/acme-challenge"
chown -R www-data:www-data "${WEBROOT}"

# ---- 3. install bootstrap config (HTTP-only) so certbot can challenge -------
if [[ ! -d /etc/letsencrypt/live/${DOMAIN} ]]; then
  log "no existing cert -> installing bootstrap (HTTP-only) nginx config..."
  cp "${BOOTSTRAP_CONF}" "${NGINX_AVAILABLE}"
  ln -sf "${NGINX_AVAILABLE}" "${NGINX_ENABLED}"
  # certbot conflicts with the default site if it owns :80
  rm -f /etc/nginx/sites-enabled/default
  nginx -t
  systemctl reload nginx || systemctl restart nginx

  # ---- 4. issue cert via webroot --------------------------------------------
  log "requesting Let's Encrypt cert for ${DOMAIN} (email=${EMAIL})..."
  certbot certonly \
    --webroot -w "${WEBROOT}" \
    -d "${DOMAIN}" \
    --non-interactive --agree-tos --no-eff-email \
    -m "${EMAIL}"
else
  log "existing cert found at /etc/letsencrypt/live/${DOMAIN} -> skipping issuance"
fi

# ---- 5. swap to full HTTPS reverse-proxy config -----------------------------
log "installing full HTTPS reverse-proxy config..."
cp "${FULL_CONF}" "${NGINX_AVAILABLE}"
ln -sf "${NGINX_AVAILABLE}" "${NGINX_ENABLED}"
nginx -t
systemctl reload nginx

# ---- 6. ensure auto-renewal timer is active ---------------------------------
log "verifying certbot.timer is enabled..."
systemctl enable --now certbot.timer
systemctl status --no-pager certbot.timer || true

# Add a deploy hook so renewals trigger nginx reload automatically
RENEWAL_HOOK_DIR="/etc/letsencrypt/renewal-hooks/deploy"
mkdir -p "${RENEWAL_HOOK_DIR}"
cat > "${RENEWAL_HOOK_DIR}/reload-nginx.sh" <<'HOOK'
#!/usr/bin/env bash
# Reload nginx after every successful Let's Encrypt renewal.
systemctl reload nginx
HOOK
chmod +x "${RENEWAL_HOOK_DIR}/reload-nginx.sh"

# ---- 7. dry-run renewal to prove the pipeline works -------------------------
log "running 'certbot renew --dry-run' to validate renewal pipeline..."
certbot renew --dry-run

log "DONE — https://${DOMAIN}/ is live and auto-renewing."
log "smoke test:"
log "  curl -I http://${DOMAIN}/    # expect 301 -> https"
log "  curl -I https://${DOMAIN}/   # expect 200/502 (502 is fine if Spring Boot is down)"
