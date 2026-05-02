# AdSignage — 음식점 주류 냉장고 디지털 광고판 송출 서비스

음식점의 주류 쇼케이스 냉장고 상단에 거치형 디지털 사이니지를 설치해 주류 메이커(진로하이트, 오비맥주, 롯데주류 등)의 광고를 송출하는 서비스입니다. 광고비의 일부는 음식점 사장님에게 분배합니다.

## 데모 시나리오 (해커톤)

1. **광고주 어드민**: 회원가입/로그인 → MP4 영상 업로드 → 시간대/일일 송출횟수 스케줄 설정
2. **광고판 재생**: 안드로이드 디바이스 2대가 스케줄에 따라 광고를 라운드 로빈 재생, 스케줄 외 시간엔 회사 로고 스플래시
3. **원격 재할당**: 어드민에서 디바이스↔음식점 매핑을 변경 → SSE 푸시로 수 초 내에 디바이스 화면 갱신

## 아키텍처

```
[Android WebView APK]            [어드민 웹]
    https://stream.owl-dev.me          /devices  /ads  /videos
    /player/{deviceId}                       │
              │                              │
              ▼                              ▼
        ┌────────────────────────────────────────┐
        │  nginx (stream.owl-dev.me, HTTPS)      │
        │   /          → Next.js  :3000          │
        │   /api, /videos → Spring Boot :8080    │
        └────────────────────────────────────────┘
                       │
                       ▼
        ┌────────────────────────────────────────┐
        │  Spring Boot 3 (Kotlin) — :8080         │
        │   • Auth (JWT)                          │
        │   • Ad / Schedule / Video CRUD          │
        │   • DeviceAssignment + 원격 매핑       │
        │   • SSE 푸시 (PLAYLIST_UPDATE,         │
        │              MAPPING_CHANGED)          │
        │   • Video Range 스트리밍              │
        └────────────────────────────────────────┘
                       │
                       ▼
            /var/lib/adsignage/videos/*.mp4
            H2 / PostgreSQL
```

## 컴포넌트

| 디렉토리 | 역할 | 빌드 |
|---|---|---|
| [backend/](./backend/README.md) | Spring Boot 3 + Kotlin REST API + SSE | `./gradlew bootJar` |
| [web/](./web/README.md) | Next.js 어드민 + 플레이어 페이지 | `npm run build` |
| [android/](./android/README.md) | Kotlin WebView 래퍼 APK | `./gradlew assembleDebug` |
| [deploy/](./deploy/README.md) | nginx, systemd, TLS 프로비저닝 스크립트 | — |

## 인프라

- **호스트**: `owl-SER8` (Ubuntu, 192.168.0.24)
- **공인 IP**: 110.8.21.243 (192.168.0.24로 포트포워딩)
- **도메인**: `stream.owl-dev.me`
- **HTTPS**: Let's Encrypt (`deploy/scripts/provision-tls.sh`)
- **백엔드 서비스**: systemd unit (`deploy/scripts/adsignage-backend.service`)

## 빠른 시작 (로컬 개발)

```bash
# 1. 백엔드
cd backend && ./gradlew bootRun

# 2. 어드민 웹
cd web && npm install && npm run dev

# 3. 안드로이드 (Android Studio에서 열거나)
cd android && ./gradlew assembleDebug
```

## 배포

```bash
# 백엔드 jar 업로드
scp backend/build/libs/adsignage-0.0.1-SNAPSHOT.jar \
    owl@110.8.21.243:/opt/adsignage/

# 서버에서
ssh owl@110.8.21.243 \
  'sudo bash /opt/adsignage/install-backend.sh'

# nginx + TLS
ssh owl@110.8.21.243 \
  'sudo bash /opt/adsignage/provision-tls.sh'
```

자세한 내용은 각 컴포넌트 README 참고.
