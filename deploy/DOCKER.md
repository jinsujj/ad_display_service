# AdSignage — Docker 운영 가이드

호스트에서 직접 jar / next 를 돌리던 systemd 방식과 동등한 동작을 컨테이너로
재구성한 구성. nginx 는 그대로 호스트에 두고 백엔드/웹 컨테이너의 publish
포트(`127.0.0.1:8080`, `127.0.0.1:3000`)로 프록시한다.

## 구성 요약

```
호스트 (owl-SER8)
 ├── nginx → 127.0.0.1:8080  (adsignage-backend 컨테이너)
 │        → 127.0.0.1:3000  (adsignage-web     컨테이너)
 │
 └── docker compose
      ├── service: backend  — Spring Boot 3.4.5 + JDK 25 (multi-stage build)
      ├── service: web      — Next.js 14 standalone (multi-stage build)
      └── volumes
           ├── adsignage-data    — H2 file DB (./data/adsignage)
           └── adsignage-videos  — 업로드된 MP4
```

이미지 두 개:
- `ghcr.io/jinsujj/adsignage-backend`
- `ghcr.io/jinsujj/adsignage-web`

## 첫 배포 (서버에서)

```bash
# 1. 코드 받기
cd /opt/adsignage
git clone https://github.com/jinsujj/ad_display_service.git src
cd src

# 2. 환경변수 채우기
cp .env.example .env
# - JWT_SECRET 은 반드시 강한 값으로:
sed -i "s|change-me-generate-with-openssl-rand-hex-64|$(openssl rand -hex 64)|" .env

# 3. (옵션) GHCR 이미지 사용 시
echo "$GHCR_PAT" | docker login ghcr.io -u jinsujj --password-stdin
docker compose pull

# 3-옵션. 로컬 빌드 (이미지 pull 대신)
docker compose build

# 4. 띄우기
docker compose up -d

# 5. 헬스 확인
docker compose ps
curl -s http://127.0.0.1:8080/actuator/health
curl -sI http://127.0.0.1:3000/ | head -1
```

## 업데이트 (이미지 교체)

CI 가 main push 시 `ghcr.io/.../adsignage-backend:main` 등을 자동 푸시한다.
서버에서는:

```bash
cd /opt/adsignage/src
git pull --ff-only            # docker-compose.yml 가 바뀌었을 수도 있으니 동기화
docker compose pull           # 새 이미지 받기
docker compose up -d          # 변경된 컨테이너만 재시작
docker image prune -f         # 옛 이미지 정리
```

특정 커밋으로 롤백:

```bash
# CI 로그에서 sha-<short> 태그 확인 후
BACKEND_IMAGE=ghcr.io/jinsujj/adsignage-backend:sha-3927fe1 docker compose up -d backend
```

## 이미지 태그 전략

`.github/workflows/docker-publish.yml` 가 push 마다 자동 태깅:

| 트리거                | 태그                                      | 용도                 |
|-----------------------|-------------------------------------------|----------------------|
| push to `main`        | `:main`, `:sha-<short>`, `:latest`        | 운영 기본            |
| tag `vX.Y.Z`          | `:X.Y.Z`, `:X.Y`, `:latest`               | 릴리즈 핀            |
| pull request          | `:pr-<number>` (push 안 함)                | Dockerfile 검증      |

`docker compose` 가 보는 이미지 이름은 `.env` 의 `BACKEND_IMAGE` /
`WEB_IMAGE` 로 override 가능. 운영은 `:main` 또는 `:sha-<short>` 핀을 권장 —
`:latest` 핀은 의도치 않은 업그레이드 위험.

## 로컬 개발

```bash
# 빌드 + 띄우기
docker compose up --build

# 로그
docker compose logs -f backend
docker compose logs -f web

# 셸로 들어가기
docker compose exec backend bash
docker compose exec web sh
```

## 주의

- **JWT_SECRET**: `.env` 에 강한 키를 반드시 셋팅. compose 가 `${JWT_SECRET:?...}`
  강제이므로 비어 있으면 up 자체가 실패.
- **NEXT_PUBLIC_API_BASE_URL**: 빌드 타임에 web 번들에 인라인. 도메인 분리
  배포라면 CI 의 GitHub Variable `NEXT_PUBLIC_API_BASE_URL` 또는 로컬
  `--build-arg` 로 주입.
- **H2 영속화**: `adsignage-data` named volume 에 H2 파일이 들어간다. 운영
  PostgreSQL 로 옮길 때는 `docker-compose.yml` 의 backend `environment` 에
  `SPRING_DATASOURCE_URL` 등을 추가하고 V103 까지의 마이그레이션을 돌리면 된다.
- **video 영속화**: `adsignage-videos` 는 호스트의 기존 `/var/lib/adsignage/videos`
  와 동기화하려면 named volume 대신 bind mount 로 바꾸는 것이 빠르다 —
  `volumes:` 항목을 `- /var/lib/adsignage/videos:/var/lib/adsignage/videos` 로
  교체.
- **SSE / Range**: nginx 설정은 그대로 — `proxy_buffering off`, `X-Accel-Buffering: no`
  등이 모두 호스트 nginx 단에 머문다. 컨테이너 내부에서는 표준 HTTP/1.1.
