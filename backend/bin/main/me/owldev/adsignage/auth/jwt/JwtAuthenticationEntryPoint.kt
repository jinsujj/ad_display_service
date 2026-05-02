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
 * AC 303의 Sub-AC 3 — JSON 401 엔트리 포인트.
 *
 * SecurityContext에 인증된 principal이 **없는** 상태로(즉, 토큰 없음,
 * 잘못된 토큰, 만료된 토큰 등 [JwtAuthenticationFilter]가 컨텍스트를 채우지
 * 못하고 떠난 모든 경우) 요청이 보호된 엔드포인트에 도달하면 스프링
 * 시큐리티가 [AuthenticationEntryPoint]를 호출.
 *
 * 기본 서블릿 컨테이너 응답은 다음 중 하나:
 *  - HTML 오류 페이지 발행, 또는
 *  - 스프링 시큐리티 기본값으로 `WWW-Authenticate: Basic realm=...` 전송 —
 *
 * Next.js 관리자 웹이나 플레이어 페이지가 둘 다 소비할 수 없음.
 *
 * 이 구현은 [me.owldev.adsignage.web.GlobalExceptionHandler.ApiError]와
 * 일치하는 JSON 본문을 생성하여, 거부의 출처(스프링 시큐리티 필터 체인 vs
 * 컨트롤러 내부에서 던져진 예외)와 무관하게 관리자 웹이 단일 오류 계약을
 * 갖도록 함.
 *
 * 동작 노트:
 *  - 예외의 전체 메시지를 의도적으로 클라이언트에 에코하지 **않음** —
 *    메시지가 내부 컨텍스트(예: "Cookie 'JSESSIONID' decoded but not
 *    associated with a session")를 누설할 수 있음. 대신 안정적인
 *    "Authentication required" 문자열을 발행. 원본 메시지는 DEBUG로
 *    로깅되어 운영자가 여전히 진단 가능.
 *  - 응답이 이미 커밋된 경우(예: 상류 필터가 SSE 스트리밍을 시작),
 *    응답을 손상시키지 않고 조용히 빠져나감.
 *  - `WWW-Authenticate`를 설정하지 않음. bearer-token 계약은 우리 API에
 *    내재되어 있으며, 헤더를 추가하면 브라우저가 관리자 웹에 네이티브
 *    Basic-auth 다이얼로그를 표시하게 됨.
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
