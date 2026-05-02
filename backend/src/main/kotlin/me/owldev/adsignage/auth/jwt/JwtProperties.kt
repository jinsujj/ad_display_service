package me.owldev.adsignage.auth.jwt

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * `application.yml`의 `adsignage.jwt` 네임스페이스 아래 JWT 설정에 대한
 * 타입 안전 바인딩.
 *
 *  - [secret] — HMAC-SHA256 서명 키. HS256에 대해 최소 32바이트(256비트)
 *    이상이어야 하며, 그렇지 않으면 jjwt가 거부함. 운영 환경에서는
 *    `JWT_SECRET` 환경 변수로 오버라이드.
 *  - [expirationMs] — 토큰 수명(밀리초 단위, 기본: 24시간).
 */
@ConfigurationProperties(prefix = "adsignage.jwt")
data class JwtProperties(
    val secret: String,
    val expirationMs: Long = 86_400_000L,
)
