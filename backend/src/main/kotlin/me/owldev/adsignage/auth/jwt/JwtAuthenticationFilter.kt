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
 * AC 301의 Sub-AC 1 — JWT 인증 필터.
 *
 * HTTP 요청당 한 번 실행됨([OncePerRequestFilter] 상속). 각 요청에 대해:
 *
 *  1. `Authorization` 헤더를 읽음.
 *  2. `Bearer `(RFC 6750에 따라 대소문자 구분 스킴)로 시작하면 원본
 *     토큰 부분을 추출.
 *  3. [JwtService.parseAuthenticatedAdvertiser]를 통해 토큰을 검증하고 파싱
 *     — HS256 서명을 *및* 만료를 검사.
 *  4. 성공 시 [AdvertiserPrincipal]을 구축하고 채워진
 *     [UsernamePasswordAuthenticationToken](`ROLE_ADVERTISER` 포함)을
 *     [SecurityContextHolder]에 설치.
 *
 * 필터는 실패 경로에서 의도적으로 관대함: 누락되거나 잘못된 토큰은 단순히
 * SecurityContext를 익명으로 남기고, 다운스트림 스프링 시큐리티 인가 규칙
 * ([me.owldev.adsignage.config.SecurityConfig]에서 별도 구성)이 요청을 거부할지
 * 결정함. 이러한 분리는 토큰 *검증*(이 필터)과 *인가*(보안 설정)를 깔끔하게
 * 분리해 줌.
 *
 * 체인 앞 단계에서 이미 SecurityContext가 채워졌다면 재인증을 건너뜀 —
 * 필터 순서의 엣지 케이스에 대한 방어적 가드.
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
        // 상류에서 이미 인증되었나? 덮어쓰지 않음.
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
                null, // 자격증명을 보관하지 않음 — 토큰은 이미 검증됨
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
            // 잘못된 서명, 만료된 토큰, 잘못된 페이로드 등.
            // SecurityContext를 비워둠; 인가 규칙이 적절히 401/403으로 보호된
            // 엔드포인트를 거부함.
            log.debug("Rejected JWT on path {}: {}", request.requestURI, ex.message)
            SecurityContextHolder.clearContext()
        } catch (ex: IllegalArgumentException) {
            // jjwt는 빈/null 토큰 문자열에 대해 IAE를 던짐.
            log.debug("Malformed JWT on path {}: {}", request.requestURI, ex.message)
            SecurityContextHolder.clearContext()
        }

        filterChain.doFilter(request, response)
    }

    /**
     * `Authorization: Bearer <token>` 헤더에서 원본 토큰을 추출. 헤더가
     * 없거나 Bearer 스킴을 사용하지 않거나 토큰 부분이 비어 있으면 `null`
     * 반환.
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
         * 성공적으로 인증된 광고주에게 부여되는 권한. 엔드포인트는
         * `.hasRole("ADVERTISER")`(스프링이 `ROLE_` 접두사를 자동으로 제거)나
         * `.authenticated()`를 통해 이를 요구할 수 있음.
         */
        const val ROLE_ADVERTISER = "ROLE_ADVERTISER"
    }
}

/**
 * 성공적인 JWT 인증 이후 [SecurityContextHolder]에 저장되는 principal.
 * 컨트롤러는 `@AuthenticationPrincipal AdvertiserPrincipal`로 가져올 수 있음.
 *
 * [AuthenticatedAdvertiser]를 미러링하지만 호출자가 내부 JWT 유틸리티
 * 타입을 임포트할 필요가 없도록 필터와 같은 패키지에 위치.
 */
data class AdvertiserPrincipal(
    val advertiserId: String,
    val email: String,
)
