#!/usr/bin/env bash
# =============================================================================
# install-web.sh
#
# Idempotent installer for the AdSignage Next.js admin/player web on owl-SER8.
# Pairs with deploy/scripts/adsignage-web.service.
#
# 동작:
#   1. node + npm 존재 확인 (>= 18 권장).
#   2. (선택) NEXT_PUBLIC_API_BASE_URL 환경변수 받아 production build.
#   3. systemd unit 설치/재로드/재시작.
#   4. /actuator/health 대신 :3002 페이지가 200 응답하는지 검증.
#
# Re-run safe: 매 단계 idempotent.
#
# 사용법:
#   sudo bash deploy/scripts/install-web.sh
#   sudo NEXT_PUBLIC_API_BASE_URL=https://stream-backend.owl-dev.me \
#        bash deploy/scripts/install-web.sh
#   sudo SKIP_BUILD=1 bash deploy/scripts/install-web.sh   # 이미 빌드된 결과 사용
# =============================================================================
set -euo pipefail

SERVICE_NAME="adsignage-web"
SERVICE_USER="${SERVICE_USER:-owl}"
SERVICE_GROUP="${SERVICE_GROUP:-owl}"

WEB_PORT="${WEB_PORT:-3002}"
WEB_HOST="${WEB_HOST:-127.0.0.1}"
NEXT_PUBLIC_API_BASE_URL="${NEXT_PUBLIC_API_BASE_URL:-https://stream-backend.owl-dev.me}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
WEB_DIR="${REPO_ROOT}/web"

SYSTEMD_SRC="${SCRIPT_DIR}/${SERVICE_NAME}.service"
SYSTEMD_DEST="/etc/systemd/system/${SERVICE_NAME}.service"

HEALTH_URL="${HEALTH_URL:-http://${WEB_HOST}:${WEB_PORT}/}"
HEALTH_TIMEOUT_SEC="${HEALTH_TIMEOUT_SEC:-60}"

SKIP_BUILD="${SKIP_BUILD:-0}"

log()  { printf '\033[1;36m[install-web]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[install-web]\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[1;31m[install-web]\033[0m %s\n' "$*" >&2; exit 1; }

[[ $EUID -eq 0 ]] || die "run as root (sudo bash $0)"
[[ -f "${SYSTEMD_SRC}" ]] || die "missing ${SYSTEMD_SRC}"
[[ -d "${WEB_DIR}" ]]    || die "missing ${WEB_DIR}"

# ---- 1. node toolchain ------------------------------------------------------
command -v node >/dev/null 2>&1 || die "node not installed (need >= 18)"
command -v npm  >/dev/null 2>&1 || die "npm not installed"
log "node: $(node -v)   npm: $(npm -v)"

# ---- 2. install + build (run as the unit user, not root) --------------------
if [[ "${SKIP_BUILD}" != "1" ]]; then
  log "installing dependencies + building production bundle..."
  log "  NEXT_PUBLIC_API_BASE_URL=${NEXT_PUBLIC_API_BASE_URL}"
  sudo -u "${SERVICE_USER}" \
    env NEXT_PUBLIC_API_BASE_URL="${NEXT_PUBLIC_API_BASE_URL}" \
    bash -lc "cd '${WEB_DIR}' && npm ci && npm run build"
fi

[[ -d "${WEB_DIR}/.next" ]] || die "${WEB_DIR}/.next not found — build failed"

# ---- 3. systemd unit --------------------------------------------------------
log "installing systemd unit -> ${SYSTEMD_DEST}"
install -m 0644 -o root -g root "${SYSTEMD_SRC}" "${SYSTEMD_DEST}"

# WEB_PORT / NEXT_PUBLIC_API_BASE_URL 환경변수가 호출자가 준 값과 다르면
# unit 파일을 in-place 갱신.
sed -i "s|^Environment=PORT=.*|Environment=PORT=${WEB_PORT}|" "${SYSTEMD_DEST}"
sed -i "s|^Environment=NEXT_PUBLIC_API_BASE_URL=.*|Environment=NEXT_PUBLIC_API_BASE_URL=${NEXT_PUBLIC_API_BASE_URL}|" "${SYSTEMD_DEST}"

systemctl daemon-reload
systemctl enable --now "${SERVICE_NAME}"
log "service enabled + started"

# ---- 4. health probe --------------------------------------------------------
log "waiting for ${HEALTH_URL} (timeout ${HEALTH_TIMEOUT_SEC}s) ..."
deadline=$(( $(date +%s) + HEALTH_TIMEOUT_SEC ))
while (( $(date +%s) < deadline )); do
  code="$(curl -s -o /dev/null -w '%{http_code}' "${HEALTH_URL}" || true)"
  case "${code}" in
    200|301|302|307|308) log "web is healthy (HTTP ${code})"; break ;;
  esac
  sleep 2
done
[[ "${code:-000}" =~ ^(200|301|302|307|308)$ ]] || die "web did not become healthy in ${HEALTH_TIMEOUT_SEC}s (last HTTP ${code:-000})"

systemctl status "${SERVICE_NAME}" --no-pager -l | head -10
log "DONE — adsignage-web is running on ${WEB_HOST}:${WEB_PORT}."
log "  tail logs: sudo journalctl -u ${SERVICE_NAME} -f"
