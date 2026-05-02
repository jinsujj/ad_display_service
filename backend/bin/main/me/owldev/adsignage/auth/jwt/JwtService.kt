package me.owldev.adsignage.auth.jwt

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets
import java.util.Date
import javax.crypto.SecretKey

/**
 * Issues HS256-signed JWT access tokens for authenticated advertisers.
 *
 * Sub-AC 3 only needs token *issuance* — verification will be wired into a
 * Spring Security filter in a later sub-AC. We expose [parseSubject] now as a
 * convenience for tests so we can prove the token is valid and contains the
 * right `sub` claim without pulling in extra plumbing.
 */
@Service
class JwtService(
    private val properties: JwtProperties,
) {

    private val signingKey: SecretKey by lazy {
        // jjwt requires HS256 keys to be ≥ 256 bits. We derive the key from the
        // configured secret's UTF-8 bytes so operators can supply a long
        // human-readable string in `application.yml` / env var.
        Keys.hmacShaKeyFor(properties.secret.toByteArray(StandardCharsets.UTF_8))
    }

    /**
     * Build a signed JWT for the given advertiser.
     *
     *  - `sub`   = advertiserId (canonical identifier — see ontology)
     *  - `email` = advertiser_email (convenience claim for the admin web)
     *  - `iat`   = now
     *  - `exp`   = now + [JwtProperties.expirationMs]
     */
    fun issueToken(advertiserId: String, email: String): IssuedToken {
        val now = System.currentTimeMillis()
        val expiresAt = now + properties.expirationMs

        val token = Jwts.builder()
            .subject(advertiserId)
            .claim("email", email)
            .issuedAt(Date(now))
            .expiration(Date(expiresAt))
            .signWith(signingKey, Jwts.SIG.HS256)
            .compact()

        return IssuedToken(token = token, expiresInMs = properties.expirationMs)
    }

    /**
     * Parse and verify a token, returning its `sub` (advertiserId).
     * Throws if the signature is invalid or the token is expired.
     */
    fun parseSubject(token: String): String {
        return parseAuthenticatedAdvertiser(token).advertiserId
    }

    /**
     * Parse and verify a token, returning the full advertiser identity.
     *
     * Used by [JwtAuthenticationFilter] (Sub-AC 1 of AC 301) to populate the
     * SecurityContext with both the advertiserId (for ownership checks) and
     * the email (for logging / convenience access via @AuthenticationPrincipal).
     *
     * Throws (subclass of) [io.jsonwebtoken.JwtException] when the signature
     * is invalid, the token is expired, or the payload is malformed.
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

        return AuthenticatedAdvertiser(advertiserId = advertiserId, email = email)
    }
}

data class IssuedToken(
    val token: String,
    val expiresInMs: Long,
)

/**
 * The verified identity asserted by a valid JWT. Used as the principal
 * stored in [org.springframework.security.core.context.SecurityContextHolder]
 * after [JwtAuthenticationFilter] runs.
 */
data class AuthenticatedAdvertiser(
    val advertiserId: String,
    val email: String,
)
