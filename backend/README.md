# AdSignage Backend (Spring Boot 3 + Kotlin)

광고주 인증, 광고/스케줄/영상 CRUD, 디바이스-음식점 매핑, SSE 푸시, 영상 Range 스트리밍을 제공하는 REST API.

## 기술 스택

- Spring Boot 3 / Kotlin 1.9
- Spring Security + JWT (jjwt)
- Spring Data JPA + H2(개발) / PostgreSQL(운영)
- Server-Sent Events (SseEmitter)
- Gradle Kotlin DSL

## 실행

```bash
# 개발 (H2 메모리 DB)
./gradlew bootRun

# 운영용 fat jar
./gradlew bootJar
java -jar build/libs/adsignage-0.0.1-SNAPSHOT.jar
```

## API 엔드포인트

| 메서드 | 경로 | 설명 | 인증 |
|---|---|---|---|
| POST | `/api/auth/signup` | 광고주 회원가입 | — |
| POST | `/api/auth/login` | 로그인 → JWT 발급 | — |
| POST | `/api/videos` | MP4 영상 업로드 (multipart) | JWT |
| GET | `/api/videos` | 본인 업로드 영상 목록 | JWT |
| GET | `/videos/{filename}` | 영상 스트리밍 (HTTP Range, 206) | — |
| POST | `/api/ads` | 광고 + 스케줄 생성 | JWT |
| GET | `/api/ads` | 본인 광고 목록 | JWT |
| GET | `/api/ads/{id}` | 광고 상세 | JWT |
| GET | `/api/devices` | 등록된 디바이스 목록 | JWT |
| PATCH | `/api/devices/{deviceId}` | 디바이스↔음식점 매핑 변경 | JWT |
| GET | `/api/devices/{deviceId}/playlist` | 디바이스용 플레이리스트 | — |
| GET | `/api/devices/{deviceId}/stream` | SSE 채널 (이벤트 푸시) | — |
| POST | `/api/play-events` | 광고 송출 증빙 이벤트 기록 | — |

## SSE 이벤트 종류

| 타입 | 트리거 | 페이로드 |
|---|---|---|
| `PLAYLIST_UPDATE` | 광고/스케줄 추가·변경 | 갱신된 플레이리스트 JSON |
| `MAPPING_CHANGED` | 디바이스-음식점 재할당 | 새 음식점 정보 + 플레이리스트 |

## 도메인 모델

```
Advertiser (광고주)
  └── Ad (광고)
        ├── Video (영상 파일)
        └── Schedule (시간대 + 일일 송출횟수)

Restaurant (음식점)
DeviceAssignment (디바이스↔음식점 매핑) — remappable

PlayEvent (송출 증빙 로그)
```

## 디렉토리 구조

```
src/main/kotlin/me/owldev/adsignage/
├── auth/             — 회원가입, 로그인, JWT
├── config/           — SecurityConfig
├── domain/
│   ├── advertiser/   — 광고주
│   ├── ad/           — 광고 + 스케줄
│   ├── video/        — 영상 업로드/저장/Range 스트리밍
│   ├── assignment/   — 디바이스↔음식점 매핑
│   └── playevent/    — 송출 증빙
├── sse/              — SseEmitterRegistry, DeviceStreamController, 이벤트 리스너
└── web/              — GlobalExceptionHandler
```

## 설정 (application.yml)

```yaml
spring:
  datasource:
    url: jdbc:h2:mem:adsignage     # 운영은 jdbc:postgresql://localhost/adsignage

adsignage:
  video:
    storage-path: /var/lib/adsignage/videos
  jwt:
    secret: ${JWT_SECRET}
    expiration: 86400000           # 24h
```

## 테스트

```bash
./gradlew test
```

27개 통합/유닛 테스트 — auth, ad, assignment, playevent, video, sse 전 영역 커버.
