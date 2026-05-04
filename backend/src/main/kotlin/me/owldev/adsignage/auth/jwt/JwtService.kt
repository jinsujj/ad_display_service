package me.owldev.adsignage.auth.jwt

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import me.owldev.adsignage.domain.advertiser.AdvertiserRole
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets
import java.util.Date
import javax.crypto.SecretKey

/**
 * 인증된 광고주를 위한 HS256 서명 JWT 액세스 토큰 발급.
 *
 * Sub-AC 3은 토큰 *발급*만 필요 — 검증은 추후 sub-AC에서 스프링 시큐리티
 * 필터에 연결됨. 테스트를 위한 편의성으로 [parseSubject]를 지금 노출하여
 * 추가 배관 없이도 토큰이 유효하고 올바른 `sub` 클레임을 포함하는지 증명할
 * 수 있게 함.
 */
@Service
class JwtService(
    private val properties: JwtProperties,
) {

    private val signingKey: SecretKey by lazy {
        // jjwt는 HS256 키가 ≥ 256 비트일 것을 요구함. 운영자가
        // `application.yml`/환경 변수에 사람이 읽을 수 있는 긴 문자열을
        // 제공할 수 있도록 설정된 시크릿의 UTF-8 바이트에서 키를 유도함.
        Keys.hmacShaKeyFor(properties.secret.toByteArray(StandardCharsets.UTF_8))
    }

    /**
     * 주어진 광고주에 대한 서명된 JWT를 빌드.
     *
     *  - `sub`   = advertiserId (정규 식별자 — 온톨로지 참조)
     *  - `email` = advertiser_email (관리자 웹을 위한 편의 클레임)
     *  - `iat`   = 현재
     *  - `exp`   = 현재 + [JwtProperties.expirationMs]
     */
    fun issueToken(
        advertiserId: String,
        email: String,
        role: AdvertiserRole = AdvertiserRole.ADVERTISER,
    ): IssuedToken {
        val now = System.currentTimeMillis()
        val expiresAt = now + properties.expirationMs

        val token = Jwts.builder()
            .subject(advertiserId)
            .claim("email", email)
            .claim("role", role.name)
            .issuedAt(Date(now))
            .expiration(Date(expiresAt))
            .signWith(signingKey, Jwts.SIG.HS256)
            .compact()

        return IssuedToken(token = token, expiresInMs = properties.expirationMs)
    }

    /**
     * 토큰을 파싱하고 검증한 뒤 `sub`(advertiserId)를 반환.
     * 서명이 잘못되었거나 토큰이 만료된 경우 던짐.
     */
    fun parseSubject(token: String): String {
        return parseAuthenticatedAdvertiser(token).advertiserId
    }

    /**
     * 토큰을 파싱하고 검증한 뒤 전체 광고주 신원을 반환.
     *
     * [JwtAuthenticationFilter](AC 301의 Sub-AC 1)이 advertiserId(소유권
     * 검사용)와 email(로깅 / @AuthenticationPrincipal을 통한 편의 접근용)
     * 모두를 SecurityContext에 채우기 위해 사용함.
     *
     * 서명이 잘못되었거나 토큰이 만료되었거나 페이로드가 잘못된 경우
     * (의 하위 클래스인) [io.jsonwebtoken.JwtException]을 던짐.
     */
    fun parseAuthenticatedAdvertiser(token: String): AuthenticatedAdvertiser {
        val claims = Jwts.parser()
            .verifyWith(signingKey)
            .build()
            .parseSignedClaims(token)
            .payload

        val advertiserId = claims.subject
            ?: throw io.jsonwebtoken.MalformedJwtException("missing sub claim")
        val email = claims["email"] as? String
            ?: throw io.jsonwebtoken.MalformedJwtException("missing email claim")
        // role claim 은 V104 이전에 발급된 토큰엔 없을 수 있다. 누락 시
        // ADVERTISER 로 폴백 — 안전한 default(권한 적은 쪽).
        val roleStr = claims["role"] as? String
        val role = when (roleStr) {
            null -> AdvertiserRole.ADVERTISER
            else -> runCatching { AdvertiserRole.valueOf(roleStr) }
                .getOrDefault(AdvertiserRole.ADVERTISER)
        }

        return AuthenticatedAdvertiser(advertiserId = advertiserId, email = email, role = role)
    }
}

data class IssuedToken(
    val token: String,
    val expiresInMs: Long,
)

/**
 * 유효한 JWT가 주장하는 검증된 신원. [JwtAuthenticationFilter] 실행 후
 * [org.springframework.security.core.context.SecurityContextHolder]에 저장된
 * principal로 사용됨.
 */
data class AuthenticatedAdvertiser(
    val advertiserId: String,
    val email: String,
    val role: AdvertiserRole = AdvertiserRole.ADVERTISER,
)
