package me.owldev.adsignage.auth.jwt

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.access.AccessDeniedException
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for [JwtAccessDeniedHandler].
 *
 * Confirms the JSON 403 contract: same envelope shape as the 401 entry point,
 * different status, stable error string.
 */
class JwtAccessDeniedHandlerTest {

    private val mapper = ObjectMapper()
    private val handler = JwtAccessDeniedHandler(mapper)

    @Test
    fun `handle emits JSON 403 with stable error envelope`() {
        val request = MockHttpServletRequest("GET", "/api/admin/secret")
        val response = MockHttpServletResponse()
        val ex = AccessDeniedException("Access is denied (rule: hasRole('ADMIN'))")

        handler.handle(request, response, ex)

        assertEquals(HttpStatus.FORBIDDEN.value(), response.status)
        // Servlet response merges contentType + charset into a single
        // header value, so we assert the prefix rather than an exact match.
        assertTrue(
            response.contentType?.startsWith(MediaType.APPLICATION_JSON_VALUE) == true,
            "content type must start with application/json, was: ${response.contentType}",
        )
        assertEquals(Charsets.UTF_8.name(), response.characterEncoding)

        val body = mapper.readTree(response.contentAsByteArray)
        assertEquals(403, body["status"].asInt())
        assertEquals("Forbidden", body["error"].asText())
        assertEquals("Access denied", body["message"].asText())
        assertNotNull(body["timestamp"])
    }

    @Test
    fun `handle is a no-op when the response is already committed`() {
        val request = MockHttpServletRequest("GET", "/api/admin/secret")
        val response = MockHttpServletResponse()
        response.setCommitted(true)
        val ex = AccessDeniedException("anything")

        handler.handle(request, response, ex)

        assertEquals(0, response.contentAsByteArray.size)
    }
}
