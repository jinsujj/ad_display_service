package me.owldev.adsignage.auth.jwt

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Type-safe binding for JWT configuration under the `adsignage.jwt` namespace
 * in `application.yml`.
 *
 *  - [secret] — HMAC-SHA256 signing key. Must be at least 32 bytes (256 bits)
 *    for HS256, otherwise jjwt will reject it. Override via the `JWT_SECRET`
 *    env var in production.
 *  - [expirationMs] — token lifetime in milliseconds (default: 24 hours).
 */
@ConfigurationProperties(prefix = "adsignage.jwt")
data class JwtProperties(
    val secret: String,
    val expirationMs: Long = 86_400_000L,
)
