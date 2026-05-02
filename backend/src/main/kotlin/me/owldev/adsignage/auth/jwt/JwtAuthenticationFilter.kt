package me.owldev.adsignage.auth.jwt

import io.jsonwebtoken.JwtException
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Sub-AC 1 of AC 301 — JWT Authentication Filter.
 *
 * Runs once per HTTP request (extends [OncePerRequestFilter]). For each
 * request it:
 *
 *  1. Reads the `Authorization` header.
 *  2. If it begins with `Bearer ` (case-sensitive scheme per RFC 6750),
 *     extracts the raw token portion.
 *  3. Validates and parses the token via [JwtService.parseAuthenticatedAdvertiser]
 *     — this verifies the HS256 signature *and* checks the expiration.
 *  4. On success, builds an [AdvertiserPrincipal] and installs a populated
 *     [UsernamePasswordAuthenticationToken] (with `ROLE_ADVERTISER`) into
 *     [SecurityContextHolder].
 *
 * The filter is intentionally permissive on failure paths: any missing or
 * malformed token simply leaves the SecurityContext anonymous, and the
 * downstream Spring Security authorization rules (configured separately in
 * [me.owldev.adsignage.config.SecurityConfig]) decide whether the request
 * should be rejected. This separation keeps token *validation* (this filter)
 * and *authorization* (security config) cleanly decoupled.
 *
 * Skips re-authentication if a SecurityContext has already been populated
 * earlier in the chain — defensive guard for filter ordering edge cases.
 */
@Component
class JwtAuthenticationFilter(
    private val jwtService: JwtService,
) : OncePerRequestFilter() {

    private val log = LoggerFactory.getLogger(JwtAuthenticationFilter::class.java)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        // Already authenticated upstream? Don't overwrite.
        if (SecurityContextHolder.getContext().authentication != null) {
            filterChain.doFilter(request, response)
            return
        }

        val token = extractBearerToken(request)
        if (token == null) {
            filterChain.doFilter(request, response)
            return
        }

        try {
            val advertiser = jwtService.parseAuthenticatedAdvertiser(token)
            val principal = AdvertiserPrincipal(
                advertiserId = advertiser.advertiserId,
                email = advertiser.email,
            )
            val authentication = UsernamePasswordAuthenticationToken(
                principal,
                null, // no credentials retained — token has already been verified
                listOf(SimpleGrantedAuthority(ROLE_ADVERTISER)),
            ).apply {
                details = WebAuthenticationDetailsSource().buildDetails(request)
            }
            SecurityContextHolder.getContext().authentication = authentication

            if (log.isDebugEnabled) {
                log.debug(
                    "Authenticated request advertiserId={} path={}",
                    advertiser.advertiserId,
                    request.requestURI,
                )
            }
        } catch (ex: JwtException) {
            // Invalid signature, expired token, malformed payload, etc.
            // Leave the SecurityContext empty; authorization rules will reject
            // protected endpoints with 401/403 as appropriate.
            log.debug("Rejected JWT on path {}: {}", request.requestURI, ex.message)
            SecurityContextHolder.clearContext()
        } catch (ex: IllegalArgumentException) {
            // jjwt throws IAE for blank/null token strings.
            log.debug("Malformed JWT on path {}: {}", request.requestURI, ex.message)
            SecurityContextHolder.clearContext()
        }

        filterChain.doFilter(request, response)
    }

    /**
     * Extract the raw token from an `Authorization: Bearer <token>` header,
     * or `null` if the header is absent / does not use the Bearer scheme /
     * the token portion is blank.
     */
    private fun extractBearerToken(request: HttpServletRequest): String? {
        val header = request.getHeader(HttpHeaders.AUTHORIZATION) ?: return null
        if (!header.startsWith(BEARER_PREFIX)) return null
        val token = header.substring(BEARER_PREFIX.length).trim()
        return token.ifBlank { null }
    }

    companion object {
        private const val BEARER_PREFIX = "Bearer "

        /**
         * Authority granted to any successfully-authenticated advertiser.
         * Endpoints can require this via `.hasRole("ADVERTISER")` (Spring
         * strips the `ROLE_` prefix automatically) or `.authenticated()`.
         */
        const val ROLE_ADVERTISER = "ROLE_ADVERTISER"
    }
}

/**
 * The principal stored in [SecurityContextHolder] after a successful JWT
 * authentication. Controllers can retrieve it with `@AuthenticationPrincipal
 * AdvertiserPrincipal`.
 *
 * Mirrors [AuthenticatedAdvertiser] but lives in the same package as the
 * filter so callers don't need to import internal JWT-utility types.
 */
data class AdvertiserPrincipal(
    val advertiserId: String,
    val email: String,
)
