package me.owldev.adsignage.auth.jwt

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.core.AuthenticationException
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * Sub-AC 3 of AC 303 — JSON 401 entry point.
 *
 * Spring Security calls into an [AuthenticationEntryPoint] when a request hits
 * a protected endpoint **without** an authenticated principal in the
 * SecurityContext (i.e. no token, malformed token, or expired token — anything
 * that left [JwtAuthenticationFilter] without populating the context).
 *
 * The default servlet container response would either:
 *  - emit an HTML error page, or
 *  - on Spring Security defaults, send `WWW-Authenticate: Basic realm=...` —
 *
 * neither of which the Next.js admin web or the player page can consume.
 *
 * This implementation produces a JSON body whose shape matches
 * [me.owldev.adsignage.web.GlobalExceptionHandler.ApiError] so the admin web
 * has a single error contract regardless of where the rejection originates
 * (Spring Security filter chain vs. a thrown exception inside a controller).
 *
 * Notes on behaviour:
 *  - We intentionally do **not** echo the exception's full message to the
 *    client — the message can leak internal context (e.g. "Cookie 'JSESSIONID'
 *    decoded but not associated with a session"). Instead we emit a stable
 *    "Authentication required" string. The original message is logged at
 *    DEBUG so operators can still diagnose.
 *  - If the response is already committed (e.g. an upstream filter started
 *    streaming SSE), we silently bail rather than corrupt the response.
 *  - We do not set `WWW-Authenticate`. The bearer-token contract is implicit
 *    in our API and adding the header would prompt browsers to display a
 *    native Basic-auth dialog on the admin web.
 */
@Component
class JwtAuthenticationEntryPoint(
    private val objectMapper: ObjectMapper,
) : AuthenticationEntryPoint {

    private val log = LoggerFactory.getLogger(JwtAuthenticationEntryPoint::class.java)

    override fun commence(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authException: AuthenticationException,
    ) {
        log.debug(
            "Unauthenticated request rejected path={} reason={}",
            request.requestURI,
            authException.message,
        )

        if (response.isCommitted) {
            log.debug("Response already committed; skipping JSON 401 body for path={}", request.requestURI)
            return
        }

        response.status = HttpStatus.UNAUTHORIZED.value()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = Charsets.UTF_8.name()

        val body = linkedMapOf<String, Any>(
            "timestamp" to Instant.now().toString(),
            "status" to HttpStatus.UNAUTHORIZED.value(),
            "error" to "Unauthorized",
            "message" to "Authentication required",
        )
        objectMapper.writeValue(response.outputStream, body)
    }
}
