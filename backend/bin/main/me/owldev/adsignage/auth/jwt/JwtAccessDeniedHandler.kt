package me.owldev.adsignage.auth.jwt

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.web.access.AccessDeniedHandler
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * Sub-AC 3 of AC 303 — JSON 403 access denied handler.
 *
 * Spring Security calls into an [AccessDeniedHandler] when a request hits a
 * protected endpoint **with** an authenticated principal whose authorities do
 * not satisfy the rule (e.g. an authenticated advertiser without
 * `ROLE_ADVERTISER`, or a future advertiser-scoped resource owned by a
 * different account once the auth-and-isolation pass lands).
 *
 * This handler is the 403 counterpart to [JwtAuthenticationEntryPoint]: same
 * JSON shape, different status code, so the admin web has a single error
 * contract for all security-layer rejections.
 *
 * Notes on behaviour:
 *  - The exception's raw message is logged at DEBUG but **not** echoed to the
 *    client; we emit a stable "Access denied" string so we don't leak rule
 *    internals to the caller.
 *  - If the response is already committed we silently bail rather than
 *    corrupt the response.
 */
@Component
class JwtAccessDeniedHandler(
    private val objectMapper: ObjectMapper,
) : AccessDeniedHandler {

    private val log = LoggerFactory.getLogger(JwtAccessDeniedHandler::class.java)

    override fun handle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        accessDeniedException: AccessDeniedException,
    ) {
        log.debug(
            "Forbidden request rejected path={} reason={}",
            request.requestURI,
            accessDeniedException.message,
        )

        if (response.isCommitted) {
            log.debug("Response already committed; skipping JSON 403 body for path={}", request.requestURI)
            return
        }

        response.status = HttpStatus.FORBIDDEN.value()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = Charsets.UTF_8.name()

        val body = linkedMapOf<String, Any>(
            "timestamp" to Instant.now().toString(),
            "status" to HttpStatus.FORBIDDEN.value(),
            "error" to "Forbidden",
            "message" to "Access denied",
        )
        objectMapper.writeValue(response.outputStream, body)
    }
}
