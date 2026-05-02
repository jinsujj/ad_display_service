#!/usr/bin/env bash
# =============================================================================
# reload-nginx.sh
#
# Safely re-sync the repo's stream.owl-dev.me nginx config to
# /etc/nginx/sites-available/, validate it with `nginx -t`, and only then
# trigger `systemctl reload nginx`.
#
# Use this AFTER `provision-tls.sh` has already issued the Let's Encrypt cert
# and installed the full HTTPS config — i.e. for routine config edits where
# you do NOT want to re-run the whole TLS provisioning flow.
#
# Validation contract:
#   1. The candidate config (BOOTSTRAP or FULL) is staged to a tmp file.
#   2. `nginx -t` runs against the live nginx tree with the candidate in
#      place via a temporary swap; on failure the previous file is restored
#      and the script exits non-zero WITHOUT touching the running service.
#   3. Only after `nginx -t` succeeds do we `systemctl reload nginx`.
#   4. After reload we re-run `nginx -t` to confirm the live state is sane.
#
# Re-running is safe and a no-op when the on-disk config already matches.
#
# Usage:
#   sudo bash deploy/scripts/reload-nginx.sh                # full HTTPS config
#   sudo MODE=bootstrap bash deploy/scripts/reload-nginx.sh # HTTP-only bootstrap
#   sudo VALIDATE_ONLY=1 bash deploy/scripts/reload-nginx.sh # nginx -t, no reload
# =============================================================================
set -euo pipefail

DOMAIN="${DOMAIN:-stream.owl-dev.me}"
MODE="${MODE:-full}"                        # full | bootstrap
VALIDATE_ONLY="${VALIDATE_ONLY:-0}"

NGINX_AVAILABLE="/etc/nginx/sites-available/${DOMAIN}.conf"
NGINX_ENABLED="/etc/nginx/sites-enabled/${DOMAIN}.conf"

# Resolve the repo root regardless of where the script is invoked from
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
BOOTSTRAP_CONF="${REPO_ROOT}/deploy/nginx/${DOMAIN}-bootstrap.conf"
FULL_CONF="${REPO_ROOT}/deploy/nginx/${DOMAIN}.conf"

case "${MODE}" in
  full)      SRC_CONF="${FULL_CONF}" ;;
  bootstrap) SRC_CONF="${BOOTSTRAP_CONF}" ;;
  *)         printf '\033[1;31m[reload-nginx]\033[0m unknown MODE=%s (expected full|bootstrap)\n' "${MODE}" >&2; exit 2 ;;
esac

log()  { printf '\033[1;36m[reload-nginx]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[reload-nginx]\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[1;31m[reload-nginx]\033[0m %s\n' "$*" >&2; exit 1; }

[[ $EUID -eq 0 ]]      || die "run as root (sudo bash $0)"
command -v nginx >/dev/null 2>&1 || die "nginx is not installed; run provision-tls.sh first"
[[ -f "${SRC_CONF}" ]] || die "missing source config: ${SRC_CONF}"

# ---- 1. stage candidate ------------------------------------------------------
log "MODE=${MODE} -> staging ${SRC_CONF} -> ${NGINX_AVAILABLE}"

# Snapshot the live file so we can roll back on validation failure. May not
# exist on the very first run.
BACKUP=""
if [[ -f "${NGINX_AVAILABLE}" ]]; then
  if cmp -s "${SRC_CONF}" "${NGINX_AVAILABLE}"; then
    log "on-disk config already matches repo — no file change needed"
  else
    BACKUP="$(mktemp "${NGINX_AVAILABLE}.bak.XXXXXX")"
    cp -p "${NGINX_AVAILABLE}" "${BACKUP}"
    log "backed up current config -> ${BACKUP}"
  fi
fi

# Atomic-ish swap: write to a sibling tmp then mv into place
TMP_DEST="$(mktemp "${NGINX_AVAILABLE}.new.XXXXXX")"
cp -p "${SRC_CONF}" "${TMP_DEST}"
mv -f "${TMP_DEST}" "${NGINX_AVAILABLE}"

# Ensure sites-enabled symlink exists and points at the right file
if [[ ! -L "${NGINX_ENABLED}" || "$(readlink -f "${NGINX_ENABLED}")" != "${NGINX_AVAILABLE}" ]]; then
  ln -sf "${NGINX_AVAILABLE}" "${NGINX_ENABLED}"
  log "(re)linked ${NGINX_ENABLED} -> ${NGINX_AVAILABLE}"
fi

# ---- 2. validate -------------------------------------------------------------
log "running 'nginx -t' to validate config..."
if ! nginx -t; then
  warn "nginx -t FAILED — rolling back"
  if [[ -n "${BACKUP}" && -f "${BACKUP}" ]]; then
    mv -f "${BACKUP}" "${NGINX_AVAILABLE}"
    warn "restored previous config from ${BACKUP}"
  else
    warn "no backup available (first install); leaving broken config staged for inspection"
  fi
  die "nginx config validation failed; nginx was NOT reloaded"
fi
log "nginx -t OK"

# Validation succeeded — discard the rollback snapshot
if [[ -n "${BACKUP}" && -f "${BACKUP}" ]]; then
  rm -f "${BACKUP}"
fi

if [[ "${VALIDATE_ONLY}" == "1" ]]; then
  log "VALIDATE_ONLY=1 -> skipping reload (config is staged + validated)"
  exit 0
fi

# ---- 3. reload ---------------------------------------------------------------
log "reloading nginx (systemctl reload nginx)..."
if ! systemctl reload nginx; then
  warn "reload failed — attempting full restart"
  systemctl restart nginx || die "nginx restart also failed; check 'journalctl -u nginx'"
fi

# ---- 4. post-reload sanity ---------------------------------------------------
log "post-reload 'nginx -t' to confirm live state..."
nginx -t
systemctl is-active --quiet nginx || die "nginx is not active after reload"

log "DONE — ${DOMAIN} (${MODE}) reloaded successfully."
