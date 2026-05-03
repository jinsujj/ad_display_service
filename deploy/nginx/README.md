# nginx — 분리 도메인 reverse proxy

owl-SER8 (192.168.0.24) 위에 두 도메인을 별도 사이트로 운영:

| 도메인 | 역할 | 업스트림 |
|---|---|---|
| **stream.owl-dev.me** | 프론트엔드 (Next.js admin + `/player/{deviceId}`) | `127.0.0.1:3002` |
| **stream-backend.owl-dev.me** | Spring Boot REST + SSE + 영상 Range + Swagger | `127.0.0.1:8082` |

분리 이유:
- 동일 도메인에 백엔드/프론트를 같이 두면 다른 사이트(`owl-blog`)가 점유한 포트와 prefix 충돌이 생김.
- 백엔드를 별도 도메인에 두니 CORS 처리만 하면 되고, 라우팅이 명확해짐.
- Next.js 어드민의 `/videos`, `/devices`, `/ads` 같은 페이지가 백엔드 alias와 충돌하지 않음.

## 이 폴더의 파일

| 파일 | 용도 |
|---|---|
| `stream.owl-dev.me.conf`              | 풀 HTTPS — 프론트엔드 전용 |
| `stream.owl-dev.me-bootstrap.conf`    | TLS 발급 전 임시 HTTP-only |
| `stream-backend.owl-dev.me.conf`      | 풀 HTTPS — 백엔드 전용 (REST/SSE/Range/Swagger) |
| `stream-backend.owl-dev.me-bootstrap.conf` | TLS 발급 전 임시 HTTP-only |
| `../scripts/provision-tls.sh`         | Let's Encrypt 발급 자동화 |
| `../scripts/reload-nginx.sh`          | repo → `/etc/nginx/sites-available` 동기화 + `nginx -t` + reload |

## 라우팅 매트릭스

### `stream.owl-dev.me` (프론트엔드)

| 경로 | 업스트림 |
|---|---|
| `http://…` | 301 → `https://…` |
| `/.well-known/acme-challenge/*` | `/var/www/certbot` |
| `/*` (catch-all) | `127.0.0.1:3002` (Next.js production) |

### `stream-backend.owl-dev.me` (백엔드)

| 경로 | 업스트림 / 메모 |
|---|---|
| `/api/devices/{id}/(stream\|events)` | `127.0.0.1:8082` — SSE 튜닝 (`proxy_buffering off`, 24h read timeout) |
| `/api/videos/{filename}` | `127.0.0.1:8082` — Range/If-Range 패스스루 |
| `/v3/api-docs`, `/swagger-ui/*` | `127.0.0.1:8082` — OpenAPI / Swagger UI |
| `/*` (catch-all) | `127.0.0.1:8082` — REST + Actuator |

업로드 한도 **512 MB** (`client_max_body_size`).

## 신규 설치 흐름

```bash
# 0. DNS A 레코드: stream.owl-dev.me, stream-backend.owl-dev.me → 110.8.21.243
# 1. bootstrap config로 nginx 가동 (HTTP only)
sudo cp deploy/nginx/stream-backend.owl-dev.me-bootstrap.conf \
        /etc/nginx/sites-available/stream-backend.owl-dev.me.conf
sudo ln -sf /etc/nginx/sites-available/stream-backend.owl-dev.me.conf \
            /etc/nginx/sites-enabled/stream-backend.owl-dev.me.conf
sudo mkdir -p /var/www/certbot
sudo nginx -t && sudo systemctl reload nginx

# 2. Let's Encrypt 인증서 발급
sudo certbot certonly --webroot -w /var/www/certbot \
     -d stream-backend.owl-dev.me \
     --non-interactive --agree-tos -m wlstncjs1234@naver.com

# 3. 풀 HTTPS config 로 교체
sudo cp deploy/nginx/stream-backend.owl-dev.me.conf \
        /etc/nginx/sites-available/stream-backend.owl-dev.me.conf
sudo nginx -t && sudo systemctl reload nginx

# 4. (프론트엔드도 같은 절차 — 도메인만 stream.owl-dev.me 로)
```

## 운영 명령

```bash
sudo nginx -t                                  # 구문 검증
sudo systemctl reload nginx                    # 무중단 reload
sudo systemctl restart nginx                   # 강제 재시작
sudo journalctl -u nginx -n 100 --no-pager     # nginx 자체 로그
sudo tail -f /var/log/nginx/stream.owl-dev.me.access.log
sudo tail -f /var/log/nginx/stream-backend.owl-dev.me.access.log
sudo certbot renew --dry-run                   # 갱신 시뮬레이션
```

## CORS

프론트(`stream.owl-dev.me`)가 백엔드(`stream-backend.owl-dev.me`)를 호출하므로 백엔드의 `CorsConfig`가 `stream.owl-dev.me`를 허용 origin에 포함해야 한다. 변경은 `backend/src/main/kotlin/me/owldev/adsignage/config/CorsConfig.kt` 또는 환경변수 `adsignage.cors.allowed-origins`로 오버라이드.
