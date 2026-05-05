# AdSignage — 음식점 주류 냉장고 디지털 광고판 송출 서비스

음식점의 주류 쇼케이스 냉장고 상단에 거치형 디지털 사이니지를 설치해 주류 메이커(진로하이트, 오비맥주, 롯데주류 등)의 광고를 송출하는 서비스입니다. 광고비의 일부는 음식점 사장님에게 분배합니다.

## 데모 시나리오 (해커톤)

1. **광고주 어드민**: 회원가입/로그인 → MP4 영상 업로드 → 시간대/일일 송출횟수 스케줄 설정
2. **광고판 재생**: 안드로이드 디바이스 2대가 스케줄에 따라 광고를 라운드 로빈 재생, 스케줄 외 시간엔 회사 로고 스플래시
3. **원격 재할당**: 어드민에서 디바이스↔음식점 매핑을 변경 → SSE 푸시로 수 초 내에 디바이스 화면 갱신

## 아키텍처

### 전체 시스템 구조

```mermaid
flowchart TB
    subgraph clients["클라이언트"]
        Browser["광고주 브라우저<br/>(Chrome/Safari/모바일)"]
        Android["Android WebView APK<br/>(광고판 디바이스 N대)"]
    end

    subgraph proxy["nginx · owl-SER8 (Ubuntu, HTTPS · Let's Encrypt)"]
        WebHost["stream.owl-dev.me<br/>→ 127.0.0.1:3002"]
        ApiHost["stream-backend.owl-dev.me<br/>→ 127.0.0.1:8080<br/><i>SSE: read_timeout 24h</i>"]
    end

    subgraph runtime["애플리케이션 (docker compose)"]
        Web["Next.js 14 어드민 + 플레이어<br/>:3000<br/><i>/login, /signup, /videos, /ads, /devices, /profile<br/>/player/&#123;deviceId&#125;</i>"]
        Backend["Spring Boot 3 (Kotlin) :8080<br/><b>JWT auth · REST · SSE · video range</b><br/><i>9 bounded contexts</i>"]
    end

    subgraph storage["저장소"]
        Postgres[("PostgreSQL<br/>schema=adsignage<br/><i>Flyway migration</i>")]
        Videos[/"호스트 볼륨<br/>/var/lib/adsignage/videos/*.mp4"/]
    end

    Browser -->|HTTPS| WebHost
    Android -->|HTTPS<br/>WebView 풀스크린| WebHost
    Browser -.->|"JWT 첨부<br/>fetch / SSE"| ApiHost
    Android -.->|"play-event POST<br/>SSE subscribe"| ApiHost

    WebHost --> Web
    ApiHost --> Backend

    Web -.->|"SSR fetch (있을 때)"| Backend
    Backend --> Postgres
    Backend -->|"MP4 read · range"| Videos
    Backend -.->|"MP4 write<br/>(video upload)"| Videos
```

### 광고 송출 + 매핑 변경 흐름 (SSE)

```mermaid
sequenceDiagram
    autonumber
    actor Operator as 운영자
    participant Web as 어드민 웹
    participant Backend as Spring Boot
    participant DB as PostgreSQL
    participant Player as Android 플레이어
    participant Video as MP4 storage

    Note over Player,Backend: 부팅 직후 SSE 구독 + 플레이리스트 fetch
    Player->>Backend: POST /api/devices/register
    Player->>Backend: GET /api/devices/{id}/stream (SSE)
    Backend-->>Player: event: CONNECTED
    Player->>Backend: GET /api/playlist?deviceId=...
    Backend->>DB: SELECT 큐 + 광고 + 매핑
    Backend-->>Player: 플레이리스트 JSON

    loop 광고 라운드 로빈
        Player->>Backend: GET /api/videos/{file} (Range)
        Backend->>Video: read range
        Video-->>Backend: bytes
        Backend-->>Player: 206 Partial Content
        Player->>Backend: POST /play-events (STARTED)
        Player->>Backend: POST /play-events (FINISHED)
    end

    Note over Operator,Backend: 운영자가 디바이스를 다른 음식점에 재할당
    Operator->>Web: PATCH /devices/{id} {restaurantId}
    Web->>Backend: PATCH /api/devices/{id}
    Backend->>DB: UPDATE assignment (active=false → 새 active)
    Backend-->>Player: SSE event: MAPPING_CHANGED
    Player->>Backend: GET /api/playlist?deviceId=... (재fetch)
    Backend-->>Player: 새 플레이리스트
    Note over Player: 새 광고로 즉시 전환 (수 초 내)
```

### 백엔드 — 9 bounded contexts (헥사고날)

```mermaid
flowchart LR
    subgraph adapters["adapter/in (REST + SSE)"]
        AuthC["AuthController<br/>/api/auth/*"]
        VideoC["VideoController · VideoStreamingController<br/>/api/videos · /api/videos/&#123;file&#125;"]
        AdC["AdController · AdPlayCountController<br/>/api/ads"]
        DeviceC["DeviceController · DeviceUpdateController<br/>/api/devices"]
        QueueC["DeviceAdQueueController<br/>/api/devices/&#123;id&#125;/queue"]
        AssignC["DeviceAssignmentController<br/>/api/devices/&#123;id&#125;/assignment"]
        RestC["RestaurantController<br/>/api/restaurants"]
        PlaylistC["PlaylistController<br/>/api/playlist"]
        StreamC["DeviceStreamController<br/>/api/devices/&#123;id&#125;/stream (SSE)"]
        PlayEventC["PlayEventController<br/>/api/devices/&#123;id&#125;/play-events"]
    end

    subgraph contexts["application/service · 9 contexts"]
        Advertiser["advertiser<br/>(JWT principal)"]
        Video["video"]
        Ad["ad"]
        Device["device"]
        Queue["queue<br/>(DeviceAdQueue)"]
        Assignment["assignment<br/>(Device ↔ Restaurant)"]
        Playlist["playlist<br/>(round-robin)"]
        Restaurant["restaurant"]
        PlayEvent["playevent"]
    end

    subgraph ports["application/port/out"]
        Repos["JPA Repository ports<br/>(Spring Data Adapter)"]
        Sse["SseEmitterRegistry<br/>(in-memory)"]
    end

    AuthC --> Advertiser
    VideoC --> Video
    AdC --> Ad
    DeviceC --> Device
    QueueC --> Queue
    AssignC --> Assignment
    RestC --> Restaurant
    PlaylistC --> Playlist
    StreamC --> Device
    PlayEventC --> PlayEvent

    Advertiser --> Repos
    Video --> Repos
    Ad --> Repos
    Device --> Repos & Sse
    Queue --> Repos
    Assignment --> Repos
    Playlist --> Repos
    Restaurant --> Repos
    PlayEvent --> Repos & Device

    Device -.->|"광고 큐 변경 시<br/>PLAYLIST_UPDATE"| Sse
    Assignment -.->|"재할당 시<br/>MAPPING_CHANGED"| Sse

    Repos --> PG[("PostgreSQL<br/>schema=adsignage")]
```

> **Legacy ASCII 다이어그램** (참고용 fallback)
>
> ```
> [Android]/[브라우저] → nginx → Next.js :3000  + Spring Boot :8080 → PostgreSQL + /var/lib/adsignage/videos
> ```

### 역할별 워크플로

#### 1. 광고주(ADVERTISER) — "내 광고 올리고 송출 결과 보기"

자기 영상·광고만 보이고, 디바이스 매핑·큐 관리는 권한이 없다 (운영자에게 매칭 요청).

```mermaid
sequenceDiagram
    autonumber
    actor Adv as 광고주<br/>(test@naver.com)
    participant Web as 어드민 웹
    participant API as Spring Boot
    participant Player as 디바이스

    Adv->>Web: 회원가입 / 로그인
    Web->>API: POST /api/auth/signup → /login
    API-->>Web: JWT (role=ADVERTISER)

    Adv->>Web: 영상 업로드 (.mp4)
    Web->>API: POST /api/videos (multipart)
    API-->>Web: 업로드 완료

    Adv->>Web: 광고 만들기<br/>(영상 select + 제목 + 시간/일일횟수 + 캠페인 기간)
    Web->>API: POST /api/ads
    API-->>Web: 201 광고 생성

    Adv->>Web: 광고 편집 페이지<br/>/ads/{id}
    Web->>API: GET /api/ads/{id}<br/>GET /api/ads/{id}/deployments (2초 폴링)
    API-->>Web: 송출 디바이스 목록 + LIVE 상태 + startedAt
    Note over Web,Adv: LIVE 카드는 디바이스가 실제로 송출 중인<br/>영상을 muted/loop 로 미러 재생
    Player->>API: play-event STARTED
    API-->>Web: deployments 다음 폴링 시 LIVE 로 갱신

    Adv->>Web: 스케줄 변경 (PUT)
    Web->>API: PUT /api/ads/{id}/schedule
    API-->>Player: SSE PLAYLIST_UPDATE
    Player->>API: 새 플레이리스트 fetch
```

#### 2. 관리자(OPERATOR) — "디바이스 큐 짜고 매핑 관리"

광고주 권한에 더해 모든 광고 풀에서 디바이스 큐를 구성하고, 디바이스↔음식점 매핑을 통제한다.

```mermaid
sequenceDiagram
    autonumber
    actor Op as 운영자<br/>(OPERATOR)
    participant Web as 어드민 웹
    participant API as Spring Boot
    participant Player as 디바이스

    Op->>Web: 로그인 → 디바이스 탭
    Web->>API: GET /api/devices (1.5초 폴링)
    API-->>Web: 디바이스 목록 + 매핑 + 큐 + LIVE 상태

    Note over Web: 모니터 wall 에 각 디바이스의<br/>실제 송출 영상 미러 표시

    Op->>Web: 디바이스 클릭 → 상세
    Web->>API: GET /api/devices/{id}
    API-->>Web: 큐 + 매핑 이력

    Op->>Web: "+ 광고 추가" → picker
    Web->>API: GET /api/ads (role=OPERATOR → 모든 광고주 풀)
    API-->>Web: 전체 광고 목록 (다른 광고주 광고 포함)
    Op->>Web: 광고 선택
    Web->>API: POST /api/devices/{id}/queue
    API-->>Player: SSE PLAYLIST_UPDATE
    Player->>API: 새 플레이리스트 fetch

    Op->>Web: 재할당 (Edit modal)
    Web->>API: PATCH /api/devices/{id} {restaurantId}
    API-->>Player: SSE MAPPING_CHANGED
    Player->>API: 새 플레이리스트 fetch (+ 음식점 컨텍스트 갱신)

    Op->>Web: 디바이스 별칭 편집 / 삭제
    Web->>API: PATCH /api/devices/{id} {deviceName}<br/>DELETE /api/devices/{id}
```

#### 3. 디바이스(Android WebView Player) — "켜지면 송출, 꺼지면 신고"

JWT 없이 작동(공개 엔드포인트만 사용). 부팅 직후 등록 → SSE 구독 → 라운드 로빈 재생.

```mermaid
stateDiagram-v2
    [*] --> Boot

    Boot --> Registered: POST /api/devices/register
    Registered --> SseConnected: GET /api/devices/&#123;id&#125;/stream
    Note right of SseConnected: SSE keepalive 10초 마다<br/>: comment 수신 →<br/>TCP dead 감지 즉시

    SseConnected --> PlaylistFetched: GET /api/playlist?deviceId=...
    PlaylistFetched --> Playing: 큐에 ACTIVE 광고 1+
    PlaylistFetched --> Idle: 큐 비었거나 시간 윈도우 밖

    Playing --> Playing: 다음 광고 (라운드 로빈)<br/>POST play-event STARTED + FINISHED<br/>(자연 heartbeat — lastSeenAt 갱신)
    Playing --> PlaylistFetched: SSE MAPPING_CHANGED<br/>또는 PLAYLIST_UPDATE

    Idle --> PlaylistFetched: SSE PLAYLIST_UPDATE<br/>(운영자가 큐에 광고 추가)
    Idle --> Playing: 시간 윈도우 진입

    Playing --> Offline: WebView pagehide<br/>POST /api/devices/&#123;id&#125;/offline (sendBeacon)
    SseConnected --> Offline: 네트워크 단절<br/>SSE keepalive 실패 → emitter unregister
    Offline --> [*]
```

#### 권한 매트릭스

| 액션 | 광고주 | 운영자 | 디바이스 |
|---|---|---|---|
| 영상 업로드/조회 (자기 것) | ✓ | ✓ | — |
| 광고 CRUD (자기 것) | ✓ | ✓ | — |
| 다른 광고주 광고 조회 | ✗ | ✓ (큐 picker) | — |
| 디바이스 매핑/재할당 | ✗ | ✓ | — |
| 디바이스 큐 관리 | ✗ | ✓ | — |
| 디바이스 등록·play-event·offline | — | — | ✓ (JWT 없음) |
| MP4 스트리밍 (`GET /api/videos/{file}`) | ✓ | ✓ | ✓ (인증 없음) |
| Profile 본인 정보 | ✓ | ✓ | — |

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

### 옵션 A — 호스트에서 직접

```bash
# 1. 백엔드
cd backend && ./gradlew bootRun

# 2. 어드민 웹
cd web && npm install && npm run dev

# 3. 안드로이드 (Android Studio에서 열거나)
cd android && ./gradlew assembleDebug
```

### 옵션 B — Docker compose

```bash
cp .env.example .env
# JWT_SECRET 채우고
docker compose up --build
# → backend  http://127.0.0.1:8080
# → web      http://127.0.0.1:3000
```

자세한 도커 운영은 [`deploy/DOCKER.md`](deploy/DOCKER.md).

## 배포

### Docker (권장)

CI(`.github/workflows/docker-publish.yml`) 가 main push 마다 ghcr 로 두 이미지를
푸시한다:

- `ghcr.io/jinsujj/adsignage-backend:{main,sha-XXXXXXX,latest}`
- `ghcr.io/jinsujj/adsignage-web:{main,sha-XXXXXXX,latest}`

서버(owl-SER8) 에서:

```bash
cd /opt/adsignage/src
git pull --ff-only
docker compose pull
docker compose up -d
```

### Legacy (systemd jar)

```bash
scp backend/build/libs/adsignage-0.0.1-SNAPSHOT.jar \
    owl@110.8.21.243:/opt/adsignage/
ssh owl@110.8.21.243 'sudo bash /opt/adsignage/install-backend.sh'
ssh owl@110.8.21.243 'sudo bash /opt/adsignage/provision-tls.sh'
```

자세한 내용은 각 컴포넌트 README 와 [`deploy/`](deploy/) 디렉토리 참고.
