package me.owldev.adsignage.config

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Contact
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.info.License
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import io.swagger.v3.oas.models.servers.Server
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * springdoc-openapi 메타데이터 설정.
 *
 * - `/v3/api-docs` (JSON), `/v3/api-docs.yaml`, `/swagger-ui.html` 노출
 * - 모든 보호된 엔드포인트는 `Authorization: Bearer <JWT>` 헤더로 호출하므로
 *   `bearerAuth` 보안 스킴을 전역 등록 → Swagger UI 우상단 "Authorize"
 *   버튼에서 토큰을 한 번 입력하면 모든 요청에 자동 첨부된다.
 *
 * 공개 라우트(예: `POST /api/auth/signup`, `GET /api/devices/{id}/stream`)에는
 * 컨트롤러에서 `@SecurityRequirements()` 어노테이션으로 토큰 요구를 끌 수
 * 있지만, 해커톤 범위에서는 전역 default만 둔다 — 토큰 없이도 호출 가능한
 * 엔드포인트는 Swagger UI에서 그냥 401이 나오면 명확히 드러난다.
 */
@Configuration
class OpenApiConfig {

    @Bean
    fun openApi(): OpenAPI {
        val bearerSchemeName = "bearerAuth"

        return OpenAPI()
            .info(
                Info()
                    .title("AdSignage Backend API")
                    .description(
                        "음식점 주류 냉장고 디지털 광고판 송출 서비스의 REST API. " +
                            "광고주 인증, 광고/스케줄/영상 CRUD, 디바이스↔음식점 매핑, " +
                            "SSE 푸시, 영상 Range 스트리밍을 제공한다.",
                    )
                    .version("0.0.1-SNAPSHOT")
                    .contact(
                        Contact()
                            .name("AdSignage Team")
                            .email("owl@owl-dev.me"),
                    )
                    .license(
                        License()
                            .name("Internal hackathon project")
                            .url("https://stream.owl-dev.me"),
                    ),
            )
            .servers(
                listOf(
                    Server().url("https://stream-backend.owl-dev.me").description("운영 백엔드 (nginx HTTPS, port 8082 reverse proxy)"),
                    Server().url("http://localhost:8080").description("로컬 개발"),
                ),
            )
            .addSecurityItem(SecurityRequirement().addList(bearerSchemeName))
            .components(
                Components()
                    .addSecuritySchemes(
                        bearerSchemeName,
                        SecurityScheme()
                            .name(bearerSchemeName)
                            .type(SecurityScheme.Type.HTTP)
                            .scheme("bearer")
                            .bearerFormat("JWT")
                            .description(
                                "`POST /api/auth/login` 응답으로 받는 JWT를 " +
                                    "`Authorization: Bearer <token>` 헤더로 전달한다.",
                            ),
                    ),
            )
    }
}
