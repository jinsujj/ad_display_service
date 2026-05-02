package me.owldev.adsignage.auth.jwt

import jakarta.servlet.FilterChain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [JwtAuthenticationFilter].
 *
 * We pair the filter with a real [JwtService] (no mocks) and exercise the
 * full token-roundtrip so the test would catch any drift between issuance
 * and parsing.
 */
class JwtAuthenticationFilterTest {

    private val jwtProperties = JwtProperties(
        secret = "unit-test-secret-unit-test-secret-unit-test-secret",
        expirationMs = 3_600_000L,
    )
    private val jwtService = JwtService(jwtProperties)
    private val filter = JwtAuthenticationFilter(jwtService)

    @AfterEach
    fun clearContext() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `valid Bearer token populates SecurityContext with AdvertiserPrincipal and ROLE_ADVERTISER`() {
        val token = jwtService.issueToken(
            advertiserId = "adv-42",
            email = "owner@example.com",
        ).token

        val request = MockHttpServletRequest("GET", "/api/protected").apply {
            addHeader(HttpHeaders.AUTHORIZATION, "Bearer $token")
        }
        val response = MockHttpServletResponse()
        val chain = RecordingFilterChain()

        filter.doFilter(request, response, chain)

        assertTrue(chain.invoked, "downstream chain must be invoked")
        val auth = SecurityContextHolder.getContext().authentication
        assertNotNull(auth, "SecurityContext must contain authentication")
        assertTrue(auth.isAuthenticated, "authentication must be marked authenticated")
        assertTrue(auth is UsernamePasswordAuthenticationToken)

        val principal = auth.principal as AdvertiserPrincipal
        assertEquals("adv-42", principal.advertiserId)
        assertEquals("owner@example.com", principal.email)

        val authorities = auth.authorities.map { it.authority }
        assertEquals(listOf(JwtAuthenticationFilter.ROLE_ADVERTISER), authorities)
    }

    @Test
    fun `missing Authorization header leaves SecurityContext empty`() {
        val request = MockHttpServletRequest("GET", "/api/protected")
        val response = MockHttpServletResponse()
        val chain = RecordingFilterChain()

        filter.doFilter(request, response, chain)

        assertTrue(chain.invoked)
        assertNull(SecurityContextHolder.getContext().authentication)
    }

    @Test
    fun `non-Bearer scheme is ignored and SecurityContext stays empty`() {
        val request = MockHttpServletRequest("GET", "/api/protected").apply {
            addHeader(HttpHeaders.AUTHORIZATION, "Basic dXNlcjpwYXNz")
        }
        val response = MockHttpServletResponse()
        val chain = RecordingFilterChain()

        filter.doFilter(request, response, chain)

        assertTrue(chain.invoked)
        assertNull(SecurityContextHolder.getContext().authentication)
    }

    @Test
    fun `malformed token is rejected and SecurityContext stays empty`() {
        val request = MockHttpServletRequest("GET", "/api/protected").apply {
            addHeader(HttpHeaders.AUTHORIZATION, "Bearer not-a-real-jwt")
        }
        val response = MockHttpServletResponse()
        val chain = RecordingFilterChain()

        filter.doFilter(request, response, chain)

        // Filter must NOT abort the chain even on bad tokens — it just
        // leaves the SecurityContext empty so the authorization rules can
        // reject the request with the appropriate status.
        assertTrue(chain.invoked)
        assertNull(SecurityContextHolder.getContext().authentication)
    }

    @Test
    fun `token signed with a different secret is rejected`() {
        // Same payload, different secret → different signature.
        val foreignService = JwtService(
            JwtProperties(
                secret = "different-secret-different-secret-different-secret",
                expirationMs = 3_600_000L,
            )
        )
        val foreignToken = foreignService.issueToken("adv-42", "owner@example.com").token

        val request = MockHttpServletRequest("GET", "/api/protected").apply {
            addHeader(HttpHeaders.AUTHORIZATION, "Bearer $foreignToken")
        }
        val response = MockHttpServletResponse()
        val chain = RecordingFilterChain()

        filter.doFilter(request, response, chain)

        assertTrue(chain.invoked)
        assertNull(SecurityContextHolder.getContext().authentication)
    }

    @Test
    fun `existing authentication is not overwritten`() {
        val pre = UsernamePasswordAuthenticationToken("pre-existing", null, emptyList())
        SecurityContextHolder.getContext().authentication = pre

        val token = jwtService.issueToken("adv-42", "owner@example.com").token
        val request = MockHttpServletRequest("GET", "/api/protected").apply {
            addHeader(HttpHeaders.AUTHORIZATION, "Bearer $token")
        }
        val response = MockHttpServletResponse()
        val chain = RecordingFilterChain()

        filter.doFilter(request, response, chain)

        assertTrue(chain.invoked)
        // Pre-existing authentication must survive.
        assertEquals(pre, SecurityContextHolder.getContext().authentication)
    }

    @Test
    fun `Bearer prefix with empty token is treated as no token`() {
        val request = MockHttpServletRequest("GET", "/api/protected").apply {
            addHeader(HttpHeaders.AUTHORIZATION, "Bearer    ")
        }
        val response = MockHttpServletResponse()
        val chain = RecordingFilterChain()

        filter.doFilter(request, response, chain)

        assertTrue(chain.invoked)
        assertNull(SecurityContextHolder.getContext().authentication)
    }

    /**
     * Minimal FilterChain that records whether it was invoked, so tests can
     * assert that the request was forwarded downstream regardless of auth
     * outcome.
     */
    private class RecordingFilterChain : FilterChain {
        var invoked: Boolean = false
        override fun doFilter(
            request: jakarta.servlet.ServletRequest,
            response: jakarta.servlet.ServletResponse,
        ) {
            invoked = true
        }
    }
}
