# AdSignage Backend (Spring Boot 3.4 + Kotlin 2.3, JDK 25)

광고주 인증, 광고/스케줄/영상 CRUD, 디바이스-음식점 매핑, SSE 푸시, 영상 Range 스트리밍을 제공하는 REST API.

## 기술 스택

| 영역 | 버전 |
|---|---|
| JDK toolchain | **25** (Temurin / OpenJDK) |
| Java source/target | 25 |
| Kotlin | 2.3.0 (jvmTarget JVM_25) |
| Gradle wrapper | **9.0.0** |
| Spring Boot | **3.4.5** |
| 인증 | Spring Security + JWT (jjwt 0.12.6) |
| ORM | Spring Data JPA + Flyway |
| DB | H2(개발) / PostgreSQL(운영) |
| 실시간 푸시 | Server-Sent Events (SseEmitter) |
| API 문서 | springdoc-openapi 2.7.0 (Swagger UI) |

## 사전 준비

JDK 25가 시스템에 있어야 합니다.

```bash
# macOS — Temurin 25
brew install --cask temurin@25

# Ubuntu 24.04+
sudo apt install -y openjdk-25-jdk
```

`gradle.properties`에 toolchain 경로가 등록되어 있어 별도 `JAVA_HOME` 설정 없이 빌드됩니다.

## 실행

```bash
# 개발 (H2 메모리 DB)
./gradlew bootRun

# 운영용 fat jar
./gradlew bootJar
java -jar build/libs/adsignage-0.0.1-SNAPSHOT.jar

# 빠른 빌드 (테스트 제외)
./gradlew build -x test
```

빌드 산출물: `build/libs/adsignage-0.0.1-SNAPSHOT.jar` (~58 MB, embedded Tomcat).

## API 문서 (Swagger UI)

`springdoc-openapi 2.7.0` 적용 — 백엔드가 떠있는 동안 다음 URL로 접속:

| 경로 | 설명 |
|---|---|
| `http://localhost:8080/swagger-ui.html` | Swagger UI (대화형 API 콘솔) |
| `http://localhost:8080/v3/api-docs` | OpenAPI 3 JSON 스펙 |
| `http://localhost:8080/v3/api-docs.yaml` | OpenAPI 3 YAML 스펙 |

운영(nginx 뒤): `https://stream.owl-dev.me/swagger-ui.html`

JWT 인증된 엔드포인트는 우상단 **Authorize** 버튼 → `Bearer <token>` 형식으로 토큰을 입력하면 모든 요청에 자동 첨부된다.

설정 파일: `src/main/kotlin/me/owldev/adsignage/config/OpenApiConfig.kt` (제목/버전/서버/보안 스킴), `application.yml`의 `springdoc.*` 키.

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
├── auth/             — 회원가입, 로그인, JWT 필터/엔트리포인트
├── config/           — SecurityConfig
├── domain/
│   ├── advertiser/   — 광고주
│   ├── ad/           — 광고 + 스케줄
│   ├── video/        — 영상 업로드/저장/Range 스트리밍
│   ├── assignment/   — 디바이스↔음식점 매핑 (JdbcDeviceLookup, RestaurantLookup)
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

## 운영 서버 배포 시 주의

운영 서버(owl-SER8)에도 JDK 25 설치 필요:

```bash
ssh owl@110.8.21.243

# Ubuntu 24.04+ 가정
sudo apt update
sudo apt install -y openjdk-25-jdk

# 또는 Temurin tarball 수동 설치
# https://adoptium.net/temurin/releases/?version=25
```

systemd 유닛(`deploy/scripts/adsignage-backend.service`)에서 `java -jar`가 JDK 25 경로를 가리키도록 `Environment=JAVA_HOME=...` 또는 절대경로 사용 권장.

## 마이그레이션 노트

- `Gradle wrapper` 8.10 → 9.0.0
- `Spring Boot` 3.3.4 → 3.4.5
- `Kotlin` 1.9.25 → 2.3.0 (jvmTarget JVM_25)
- `Java toolchain` 17 → 25
- `EntityLookup.kt`: `JdbcTemplate.queryForObject`의 nullable 반환 처리 (Kotlin 2.x nullability strict)
- `gradle.properties` 신규 — toolchain 자동 감지, JDK 25 경로 등록
- `springdoc-openapi 2.7.0` 추가 — Swagger UI / OpenAPI 3 스펙

## IDE 호환성 메모

Kotlin 2.3 metadata는 **JetBrains Kotlin 플러그인 2.3+** 가 필요합니다.

- **IntelliJ IDEA 2024.3+**: 플러그인 업데이트(`Settings → Plugins → Kotlin → Update`) 후 정상 동작.
- **IntelliJ IDEA 2024.2 이하**: 본체 업그레이드 필요(아니면 `INCOMPATIBLE_CLASS` 경고).
- **VS Code (`fwcd.kotlin`)**: Kotlin LSP가 2.x stdlib를 잘 못 읽음 — 빌드는 Gradle이 처리하므로 동작은 하지만 자동완성/오류 표시는 제한.
- **JetBrains Fleet**: 무료, Kotlin 2.3 완전 지원.
