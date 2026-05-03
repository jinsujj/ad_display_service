#!/usr/bin/env bash
# =============================================================================
# install-backend.sh
#
# Idempotent installer for the AdSignage Spring Boot backend on owl-SER8.
# Pairs with deploy/scripts/adsignage-backend.service.
#
# What it does:
#   1.  Verify dependencies (java >= 17). Optionally install temurin-17-jre.
#   2.  Build the fat jar with Gradle (skippable via SKIP_BUILD=1, useful when
#       the jar was built in CI and copied to the host).
#   3.  Create the `adsignage` system user/group.
#   4.  Lay out /opt/adsignage/backend, /var/lib/adsignage/{data,videos},
#       and /etc/adsignage.
#   5.  Copy the fat jar to /opt/adsignage/backend/adsignage-backend.jar.
#   6.  Seed /etc/adsignage/backend.env from a template (only on first run —
#       never overwrites an existing file containing real secrets).
#   7.  Install the systemd unit, daemon-reload, enable, and (re)start it.
#   8.  Poll /actuator/health until it returns 200 (or fail loudly).
#
# Re-run safely: every step is idempotent.
#
# Usage:
#   sudo bash deploy/scripts/install-backend.sh
#   sudo SKIP_BUILD=1 bash deploy/scripts/install-backend.sh   # use existing JAR
#   sudo JAR_PATH=/tmp/adsignage.jar bash deploy/scripts/install-backend.sh
# =============================================================================
set -euo pipefail

SERVICE_NAME="adsignage-backend"
SERVICE_USER="adsignage"
SERVICE_GROUP="adsignage"

INSTALL_DIR="/opt/adsignage/backend"
STATE_DIR="/var/lib/adsignage"
VIDEO_DIR="${STATE_DIR}/videos"
DATA_DIR="${STATE_DIR}/data"
CONF_DIR="/etc/adsignage"
ENV_FILE="${CONF_DIR}/backend.env"

JAR_DEST="${INSTALL_DIR}/adsignage-backend.jar"
SYSTEMD_DEST="/etc/systemd/system/${SERVICE_NAME}.service"

# Resolve the repo root regardless of where the script is invoked from
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
BACKEND_DIR="${REPO_ROOT}/backend"
SYSTEMD_SRC="${SCRIPT_DIR}/${SERVICE_NAME}.service"

JAR_PATH="${JAR_PATH:-}"   # if set, skip build and use this jar
SKIP_BUILD="${SKIP_BUILD:-0}"
HEALTH_URL="${HEALTH_URL:-http://127.0.0.1:8080/actuator/health}"
HEALTH_TIMEOUT_SEC="${HEALTH_TIMEOUT_SEC:-90}"

log()  { printf '\033[1;36m[install-backend]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[install-backend]\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[1;31m[install-backend]\033[0m %s\n' "$*" >&2; exit 1; }

[[ $EUID -eq 0 ]] || die "run as root (sudo bash $0)"
[[ -f "${SYSTEMD_SRC}" ]] || die "missing ${SYSTEMD_SRC}"

# ---- 1. java toolchain -------------------------------------------------------
need_java() {
  if ! command -v java >/dev/null 2>&1; then return 1; fi
  local v
  v="$(java -version 2>&1 | awk -F\" '/version/ {print $2; exit}')"
  # accept 17 / 21 / etc; reject 1.8 / 11
  case "$v" in
    17.*|18.*|19.*|20.*|21.*|22.*|23.*|24.*|25.*) return 0 ;;
    *) return 1 ;;
  esac
}

if ! need_java; then
  log "installing OpenJDK 17 (default-jre headless)..."
  export DEBIAN_FRONTEND=noninteractive
  apt-get update -y
  apt-get install -y openjdk-17-jre-headless
  need_java || die "java still not >= 17 after install"
fi
log "java: $(java -version 2>&1 | head -n1)"

# ---- 2. build fat jar --------------------------------------------------------
if [[ -z "${JAR_PATH}" ]]; then
  if [[ "${SKIP_BUILD}" == "1" ]]; then
    # Pick the newest bootJar artifact in build/libs that isn't *-plain.jar
    JAR_PATH="$(ls -1t "${BACKEND_DIR}/build/libs"/*.jar 2>/dev/null \
                  | grep -v -- '-plain\.jar$' | head -n1 || true)"
    [[ -n "${JAR_PATH}" ]] || die "SKIP_BUILD=1 but no jar in ${BACKEND_DIR}/build/libs"
  else
    log "building backend (./gradlew bootJar) ..."
    ( cd "${BACKEND_DIR}" && ./gradlew --no-daemon -q clean bootJar )
    JAR_PATH="$(ls -1t "${BACKEND_DIR}/build/libs"/*.jar \
                  | grep -v -- '-plain\.jar$' | head -n1)"
    [[ -n "${JAR_PATH}" ]] || die "bootJar produced no artifact in build/libs"
  fi
fi
[[ -f "${JAR_PATH}" ]] || die "JAR_PATH does not exist: ${JAR_PATH}"
log "using jar: ${JAR_PATH}"

# ---- 3. user / group ---------------------------------------------------------
if ! getent group "${SERVICE_GROUP}" >/dev/null; then
  log "creating group ${SERVICE_GROUP}..."
  groupadd --system "${SERVICE_GROUP}"
fi
if ! id -u "${SERVICE_USER}" >/dev/null 2>&1; then
  log "creating user ${SERVICE_USER}..."
  useradd --system --gid "${SERVICE_GROUP}" \
          --home-dir "${STATE_DIR}" --no-create-home \
          --shell /usr/sbin/nologin "${SERVICE_USER}"
fi

# ---- 4. directories ----------------------------------------------------------
log "preparing directories..."
install -d -m 0755 -o root             -g root             "${INSTALL_DIR}"
install -d -m 0750 -o "${SERVICE_USER}" -g "${SERVICE_GROUP}" "${STATE_DIR}"
install -d -m 0750 -o "${SERVICE_USER}" -g "${SERVICE_GROUP}" "${VIDEO_DIR}"
install -d -m 0750 -o "${SERVICE_USER}" -g "${SERVICE_GROUP}" "${DATA_DIR}"
install -d -m 0750 -o root             -g "${SERVICE_GROUP}" "${CONF_DIR}"

# ---- 5. install jar ----------------------------------------------------------
log "installing jar -> ${JAR_DEST}"
install -m 0644 -o root -g root "${JAR_PATH}" "${JAR_DEST}"

# ---- 6. seed env file (first run only) --------------------------------------
if [[ ! -f "${ENV_FILE}" ]]; then
  log "seeding ${ENV_FILE} (first run)..."
  # Generate a strong default JWT_SECRET so the service does not fall back to
  # the dev-default baked into application.yml. Operator can replace later.
  GENERATED_JWT="$(head -c 48 /dev/urandom | base64 | tr -d '\n=+/' | head -c 64)"
  umask 077
  cat > "${ENV_FILE}" <<EOF
# /etc/adsignage/backend.env
# Loaded by systemd via EnvironmentFile=. Override per-host secrets here.
# Owner: root:adsignage  Mode: 0640.
JWT_SECRET=${GENERATED_JWT}
VIDEO_STORAGE_PATH=${VIDEO_DIR}
EOF
  chown root:"${SERVICE_GROUP}" "${ENV_FILE}"
  chmod 0640 "${ENV_FILE}"
else
  log "${ENV_FILE} already exists -> leaving it untouched"
fi

# ---- 7. install systemd unit -------------------------------------------------
log "installing systemd unit -> ${SYSTEMD_DEST}"
install -m 0644 -o root -g root "${SYSTEMD_SRC}" "${SYSTEMD_DEST}"
systemctl daemon-reload
systemctl enable "${SERVICE_NAME}"
log "(re)starting ${SERVICE_NAME}..."
systemctl restart "${SERVICE_NAME}"

# ---- 8. wait for health -----------------------------------------------------
log "waiting for ${HEALTH_URL} (timeout ${HEALTH_TIMEOUT_SEC}s) ..."
deadline=$(( $(date +%s) + HEALTH_TIMEOUT_SEC ))
while :; do
  if curl -fsS --max-time 3 "${HEALTH_URL}" >/dev/null 2>&1; then
    log "backend is healthy."
    break
  fi
  if (( $(date +%s) >= deadline )); then
    warn "backend did not become healthy in ${HEALTH_TIMEOUT_SEC}s"
    warn "recent logs:"
    journalctl -u "${SERVICE_NAME}" --no-pager -n 60 >&2 || true
    die "health check failed"
  fi
  sleep 2
done

systemctl --no-pager --full status "${SERVICE_NAME}" || true
log "DONE — ${SERVICE_NAME} is running. Tail logs with:"
log "  sudo journalctl -u ${SERVICE_NAME} -f"
