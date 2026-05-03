# =============================================================================
# AdSignage Backend Dockerfile (Spring Boot 3.4.5 + Kotlin 2.3 + JDK 25)
#
# Multi-stage:
#   1) build  — gradle 캐시를 잘 쓰도록 의존성 먼저 받고 그 다음 소스 빌드
#   2) runtime — JRE 25 + non-root 유저 + bootJar 만 담아 가벼운 이미지
#
# 빌드 결과는 BOOT-INF 의존성을 layered jar 로 풀어 docker layer 캐시를 한
# 번 더 활용한다(deps 가 거의 안 바뀌므로 소스만 바뀐 빌드는 작은 layer 만
# 갱신).
#
# 사용:
#   docker build -t adsignage-backend:local .
#   docker run --rm -p 8080:8080 \
#     -e JWT_SECRET=$(openssl rand -hex 64) \
#     -v adsignage-data:/var/lib/adsignage/data \
#     -v adsignage-videos:/var/lib/adsignage/videos \
#     adsignage-backend:local
# =============================================================================

# -----------------------------------------------------------------------------
# 1) build stage
# -----------------------------------------------------------------------------
FROM eclipse-temurin:25-jdk AS build

WORKDIR /workspace

# 메타 + wrapper 만 먼저 복사. gradle wrapper download 만 따로 한 layer 로
# 분리하면, 소스만 바뀐 빌드는 wrapper 재다운로드 없이 진행된다.
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
RUN chmod +x gradlew \
 && ./gradlew --no-daemon --version > /dev/null 2>&1 || true

# 소스 복사 후 fat jar 빌드. 테스트는 CI 에서 따로 도는 가정으로 -x test.
COPY src src
RUN ./gradlew --no-daemon clean bootJar -x test

# layered jar 추출 — Spring Boot 3.x 가 기본 지원.
RUN mkdir -p /workspace/extracted \
 && cd /workspace/extracted \
 && cp /workspace/build/libs/*.jar app.jar \
 && java -Djarmode=layertools -jar app.jar extract \
 && rm app.jar

# -----------------------------------------------------------------------------
# 2) runtime stage
# -----------------------------------------------------------------------------
FROM eclipse-temurin:25-jre

# OCI labels — ghcr 페이지에서 소스/라이선스/설명이 자동 표시되도록.
LABEL org.opencontainers.image.title="AdSignage Backend" \
      org.opencontainers.image.description="Spring Boot API for the AdSignage digital signage platform" \
      org.opencontainers.image.source="https://github.com/jinsujj/ad_display_service" \
      org.opencontainers.image.licenses="MIT"

# curl: HEALTHCHECK 가 /actuator/health 를 폴링할 때 사용. 베이스 이미지에
# 기본 포함되지 않을 수 있으므로 명시 설치. apt 메타는 같은 RUN 에서 정리해
# layer size 를 작게 유지.
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*

# non-root 운영. temurin 베이스(Ubuntu) 가 이미 UID 1000 의 ubuntu 유저를
# 가지고 있어 1000 은 피하고 1001 을 쓴다. 호스트 bind mount 로 /var/lib/adsignage
# 를 노출할 때 UID/GID 가 안 맞으면 권한 문제가 생기므로 compose 쪽에서
# `user: "1001:1001"` 로 핀하거나 named volume 을 권장.
RUN groupadd --system --gid 1001 adsignage \
 && useradd  --system --uid 1001 --gid adsignage --create-home --home-dir /home/adsignage adsignage \
 && mkdir -p /var/lib/adsignage/data /var/lib/adsignage/videos /opt/adsignage/data \
 && chown -R adsignage:adsignage /var/lib/adsignage /opt/adsignage
# /opt/adsignage/data 를 미리 만들어 두는 이유:
#   Docker 가 named volume 을 *처음 마운트* 할 때 image 의 해당 경로에 있던
#   디렉토리 내용 + ownership 을 그대로 volume 으로 복사한다. 이 라인이 없으면
#   compose 가 /opt/adsignage/data 마운트 시 root 소유의 빈 volume 이 만들어져
#   non-root 유저(uid 1001)가 H2 lock 파일을 쓰지 못해 부팅 실패.

WORKDIR /opt/adsignage

# layered 추출물 복사. 변동이 적은 layer 부터 깔아 캐시 hit 율을 높인다.
COPY --from=build --chown=adsignage:adsignage /workspace/extracted/dependencies/         ./
COPY --from=build --chown=adsignage:adsignage /workspace/extracted/spring-boot-loader/   ./
COPY --from=build --chown=adsignage:adsignage /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=adsignage:adsignage /workspace/extracted/application/           ./

USER adsignage

# 비디오 + H2 데이터 파일 영속화용 볼륨. 기본 application.yml 의 H2 file URL
# (`jdbc:h2:file:./data/adsignage`) 이 WORKDIR 기준 상대경로이므로 ./data 가
# 컨테이너 안 /opt/adsignage/data 에 떨어진다. 운영 시에는 H2 보다 외부 DB
# 권장 — VOLUME 선언은 H2 모드 디폴트를 위한 안전망.
VOLUME ["/var/lib/adsignage/data", "/var/lib/adsignage/videos"]

# 비디오 저장 경로는 application.yml 에서 ${VIDEO_STORAGE_PATH} 로 override
# 가능. 컨테이너에서는 named volume 마운트를 가리키도록 환경변수로 주는 게
# 깔끔하므로 기본값을 명시한다.
ENV VIDEO_STORAGE_PATH=/var/lib/adsignage/videos \
    SPRING_PROFILES_ACTIVE=prod \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/./urandom"

EXPOSE 8080

# /actuator/health 가 200 이면 정상. start-period 는 Flyway 마이그레이션 +
# H2 초기화에 여유를 두기 위해 60s.
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD ["sh", "-c", "curl -fsS http://127.0.0.1:8080/actuator/health | grep -q '\"status\":\"UP\"' || exit 1"]

ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
