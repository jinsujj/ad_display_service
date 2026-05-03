# =============================================================================
# AdSignage Admin Web Dockerfile (Next.js 14 App Router)
#
# Multi-stage:
#   1) deps    — npm ci 만 따로 → package-lock 변경 없으면 layer 재사용
#   2) build   — 소스 + .next standalone 빌드
#   3) runtime — node:20-alpine + standalone tree 만 복사한 슬림 이미지
#
# Next.js 의 standalone output(`next.config.mjs` 에서 `output: 'standalone'`)
# 덕분에 runtime 에는 next 바이너리도, 모든 dev deps 도 필요 없다 — 빌드가
# 추려 준 server.js + .next + 필수 node_modules 트리만 들어간다.
#
# 사용:
#   docker build -t adsignage-web:local --build-arg NEXT_PUBLIC_API_BASE_URL=https://stream-backend.owl-dev.me .
#   docker run --rm -p 3000:3000 adsignage-web:local
# =============================================================================

# -----------------------------------------------------------------------------
# 1) deps
# -----------------------------------------------------------------------------
FROM node:20-alpine AS deps

# next 빌드 중에 sharp 가 native 모듈을 빌드할 수 있도록 libc6-compat. 추가
# 비용이 적고, 향후 image optimization 켰을 때 silent crash 를 막는다.
RUN apk add --no-cache libc6-compat

WORKDIR /app

COPY package.json package-lock.json* ./
RUN if [ -f package-lock.json ]; then \
      npm ci; \
    else \
      npm install --no-audit --no-fund; \
    fi

# -----------------------------------------------------------------------------
# 2) build
# -----------------------------------------------------------------------------
FROM node:20-alpine AS build

WORKDIR /app

COPY --from=deps /app/node_modules ./node_modules
COPY . .

# NEXT_PUBLIC_* 는 빌드 타임에 번들에 인라인된다. 백엔드 도메인이 분리된
# 운영 환경(stream.owl-dev.me 프론트 ↔ stream-backend.owl-dev.me 백엔드)
# 에서는 빌드 시점에 이 값을 주입해야 한다.
ARG NEXT_PUBLIC_API_BASE_URL=""
ENV NEXT_PUBLIC_API_BASE_URL=$NEXT_PUBLIC_API_BASE_URL

# 빌드 단계에서는 텔레메트리 끄고, 표준 빌드. standalone output 은
# next.config.mjs 의 `output: 'standalone'` 로 활성화되어 있어 자동으로
# .next/standalone/ 트리가 생성된다.
ENV NEXT_TELEMETRY_DISABLED=1
RUN npm run build

# -----------------------------------------------------------------------------
# 3) runtime
# -----------------------------------------------------------------------------
FROM node:20-alpine AS runtime

LABEL org.opencontainers.image.title="AdSignage Admin Web" \
      org.opencontainers.image.description="Next.js admin + player UI for AdSignage" \
      org.opencontainers.image.source="https://github.com/jinsujj/ad_display_service" \
      org.opencontainers.image.licenses="MIT"

WORKDIR /app

# non-root 운영. node:20-alpine 에는 이미 `node:node` 가 있어 그대로 사용.
ENV NODE_ENV=production \
    NEXT_TELEMETRY_DISABLED=1 \
    PORT=3000 \
    HOSTNAME=0.0.0.0

# standalone tree 만 복사 — server.js, 추려진 node_modules, .next/server.
# public/ 와 .next/static 은 standalone 구조 밖에 따로 있어야 하므로 별도 복사.
COPY --from=build --chown=node:node /app/public ./public
COPY --from=build --chown=node:node /app/.next/standalone ./
COPY --from=build --chown=node:node /app/.next/static ./.next/static

USER node

EXPOSE 3000

# Next.js standalone server. 컨테이너 내에서 0.0.0.0 으로 바인딩되어야
# docker port mapping 이 동작 — Dockerfile env HOSTNAME 으로 설정.
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
  CMD ["sh", "-c", "wget -qO- http://127.0.0.1:3000/ >/dev/null 2>&1 || exit 1"]

CMD ["node", "server.js"]
