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
 * AC 303의 Sub-AC 3 — JSON 403 access denied 핸들러.
 *
 * 인증된 principal이 **있지만** 그 권한이 규칙을 만족하지 못한 상태로
 * (예: `ROLE_ADVERTISER`가 없는 인증된 광고주, 또는 auth-and-isolation
 * 패스가 도착한 후 다른 계정이 소유한 향후 광고주 범위 리소스) 요청이
 * 보호된 엔드포인트에 도달하면 스프링 시큐리티가 [AccessDeniedHandler]를
 * 호출.
 *
 * 이 핸들러는 [JwtAuthenticationEntryPoint]의 403 짝: 동일한 JSON 모양,
 * 다른 상태 코드. 따라서 관리자 웹이 모든 보안 레이어 거부에 대해 단일
 * 오류 계약을 가짐.
 *
 * 동작 노트:
 *  - 예외의 원본 메시지는 DEBUG로 로깅되지만 클라이언트에 에코되지
 *    **않음**; 호출자에게 규칙 내부 정보를 누설하지 않도록 안정적인
 *    "Access denied" 문자열을 발행.
 *  - 응답이 이미 커밋된 경우 응답을 손상시키지 않고 조용히 빠져나감.
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
