package me.owldev.adsignage.auth.jwt

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.BadCredentialsException
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for [JwtAuthenticationEntryPoint].
 *
 * Confirms the JSON 401 contract that the admin web + player page rely on:
 *  - status 401
 *  - Content-Type application/json
 *  - body keys: timestamp, status, error, message
 *  - the raw exception message is NOT echoed verbatim (no info leak)
 */
class JwtAuthenticationEntryPointTest {

    private val mapper = ObjectMapper()
    private val entryPoint = JwtAuthenticationEntryPoint(mapper)

    @Test
    fun `commence emits JSON 401 with stable error envelope`() {
        val request = MockHttpServletRequest("GET", "/api/protected")
        val response = MockHttpServletResponse()
        val ex = BadCredentialsException("internal: cookie decoded but no session")

        entryPoint.commence(request, response, ex)

        assertEquals(HttpStatus.UNAUTHORIZED.value(), response.status)
        // Servlet response merges contentType + charset into a single
        // header value, so we assert the prefix rather than an exact match.
        assertTrue(
            response.contentType?.startsWith(MediaType.APPLICATION_JSON_VALUE) == true,
            "content type must start with application/json, was: ${response.contentType}",
        )
        assertEquals(Charsets.UTF_8.name(), response.characterEncoding)

        val body = mapper.readTree(response.contentAsByteArray)
        assertEquals(401, body["status"].asInt())
        assertEquals("Unauthorized", body["error"].asText())
        assertEquals("Authentication required", body["message"].asText())
        assertNotNull(body["timestamp"])
        // The internal exception message must not leak to the client.
        assertTrue(
            !body["message"].asText().contains("cookie"),
            "exception message must not be echoed to the client",
        )
    }

    @Test
    fun `commence is a no-op when the response is already committed`() {
        val request = MockHttpServletRequest("GET", "/api/protected")
        val response = MockHttpServletResponse()
        // Force-commit the response by writing past the buffer limit and flushing.
        response.setCommitted(true)
        val ex = BadCredentialsException("anything")

        entryPoint.commence(request, response, ex)

        // No status / content-type override; body remains empty.
        assertEquals(0, response.contentAsByteArray.size)
    }
}
