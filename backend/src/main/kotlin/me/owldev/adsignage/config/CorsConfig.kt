package me.owldev.adsignage.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.UrlBasedCorsConfigurationSource

/**
 * 프론트엔드(`stream.owl-dev.me`)가 백엔드(`stream-backend.owl-dev.me`)를
 * 호출할 때 발생하는 CORS preflight/실 요청을 처리하기 위한 설정.
 *
 * 기본 허용 origin은 프로덕션 도메인 + 로컬 개발 두 가지.
 * `adsignage.cors.allowed-origins`(comma-separated) 환경변수로 오버라이드 가능.
 *
 * 쿠키 기반 인증을 쓰지 않고 `Authorization: Bearer <jwt>` 만 사용하므로
 * `allowCredentials = false`로 두고 모든 헤더를 허용한다.
 * (allowCredentials=true 와 wildcard 헤더는 함께 못 쓰는 제약 우회)
 *
 * SecurityFilterChain의 `.cors { }` 블록이 이 [UrlBasedCorsConfigurationSource]
 * 빈을 자동으로 감지해 적용한다.
 */
@Configuration
class CorsConfig(
    @Value("\${adsignage.cors.allowed-origins:https://stream.owl-dev.me,http://localhost:3000,http://localhost:3002}")
    private val allowedOriginsCsv: String,
) {

    @Bean
    fun corsConfigurationSource(): UrlBasedCorsConfigurationSource {
        val originList = allowedOriginsCsv.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val cfg = CorsConfiguration().apply {
            // 정확 일치 origin 목록 — 콤마로 분리, 공백 trim 후 setter 호출.
            allowedOrigins = originList
            allowedMethods = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "HEAD")
            allowedHeaders = listOf("*")
            // 클라이언트가 읽어야 할 응답 헤더(특히 SSE/Range 응답 디버깅용).
            exposedHeaders = listOf(
                "Content-Range",
                "Accept-Ranges",
                "Content-Length",
                "Authorization",
            )
            allowCredentials = false
            // preflight 캐시 1시간 — 같은 origin/메서드 조합을 매 요청마다 OPTIONS
            // 왕복 안 시키도록 함.
            maxAge = 3600
        }
        return UrlBasedCorsConfigurationSource().apply {
            registerCorsConfiguration("/**", cfg)
        }
    }
}
