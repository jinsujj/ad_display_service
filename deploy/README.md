# AdSignage Deploy

owl-SER8(Ubuntu, 192.168.0.24, 공인 IP 110.8.21.243) 서버에 배포하기 위한 nginx 설정과 운영 스크립트 모음.

## 디렉토리

```
deploy/
├── nginx/
│   ├── stream.owl-dev.me.conf            — 운영용 (HTTPS)
│   └── stream.owl-dev.me-bootstrap.conf  — TLS 발급 전 임시 (HTTP only)
└── scripts/
    ├── adsignage-backend.service         — systemd 유닛
    ├── install-backend.sh                — 백엔드 설치 + systemd 등록
    ├── provision-tls.sh                  — Let's Encrypt 인증서 발급
    ├── reload-nginx.sh                   — 설정 검증 후 reload
    └── verify-public-access.sh           — 외부 접근 확인 (curl)
```

## 배포 순서

### 1. 백엔드 설치

```bash
# 로컬에서 jar 업로드
scp backend/build/libs/adsignage-0.0.1-SNAPSHOT.jar \
    owl@110.8.21.243:/opt/adsignage/
scp deploy/scripts/* owl@110.8.21.243:/opt/adsignage/

# 서버에서 설치
ssh owl@110.8.21.243
sudo bash /opt/adsignage/install-backend.sh
# → /etc/systemd/system/adsignage-backend.service 등록 + 시작
sudo systemctl status adsignage-backend
```

### 2. nginx 부트스트랩 (HTTP only) — TLS 발급 전 단계

```bash
sudo cp /opt/adsignage/stream.owl-dev.me-bootstrap.conf \
        /etc/nginx/sites-available/stream.owl-dev.me
sudo ln -s /etc/nginx/sites-available/stream.owl-dev.me \
           /etc/nginx/sites-enabled/
sudo bash /opt/adsignage/reload-nginx.sh
```

### 3. TLS 발급

```bash
sudo bash /opt/adsignage/provision-tls.sh
# → certbot --nginx -d stream.owl-dev.me
```

### 4. 운영 nginx 설정으로 교체

```bash
sudo cp /opt/adsignage/stream.owl-dev.me.conf \
        /etc/nginx/sites-available/stream.owl-dev.me
sudo bash /opt/adsignage/reload-nginx.sh
```

### 5. 검증

```bash
bash /opt/adsignage/verify-public-access.sh
# → https://stream.owl-dev.me/api/health 200 확인
```

## nginx 라우팅 규칙

```
https://stream.owl-dev.me
  ├── /              → http://127.0.0.1:3000  (Next.js 어드민/플레이어)
  ├── /api/...       → http://127.0.0.1:8080  (Spring Boot)
  ├── /videos/...    → http://127.0.0.1:8080  (영상 Range 스트리밍)
  └── /api/devices/{id}/stream  → SSE (proxy_buffering off, X-Accel-Buffering: no)
```

## DNS

```
stream.owl-dev.me  A  110.8.21.243
```

(공인 IP 110.8.21.243 → 공유기 포트포워딩 → 192.168.0.24:80,443)

## 운영 명령

```bash
# 백엔드 재시작
sudo systemctl restart adsignage-backend
sudo journalctl -u adsignage-backend -f

# nginx
sudo systemctl reload nginx
sudo nginx -t

# 인증서 갱신 (cron으로 자동)
sudo certbot renew
```
