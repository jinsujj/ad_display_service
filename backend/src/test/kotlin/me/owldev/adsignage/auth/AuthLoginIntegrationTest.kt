package me.owldev.adsignage.auth

import com.fasterxml.jackson.databind.ObjectMapper
import me.owldev.adsignage.auth.dto.LoginRequest
import me.owldev.adsignage.auth.dto.SignupRequest
import me.owldev.adsignage.auth.jwt.JwtService
import me.owldev.adsignage.domain.advertiser.AdvertiserRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Sub-AC 3 verification: POST /api/auth/login
 *  - 200 on success, returns a signed JWT whose `sub` claim equals the
 *    advertiserId, plus the email and a "Bearer" tokenType.
 *  - 401 on wrong password.
 *  - 401 on unknown email (same code as wrong password — we deliberately do
 *    not leak which condition was violated).
 *  - 400 on payload validation failures (invalid email / blank password).
 *  - Email matching is case-insensitive (signup normalises to lowercase, so
 *    login must too).
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(
    properties = [
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:auth-login-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        // Pinned secret so the test can deterministically verify the signature.
        "adsignage.jwt.secret=test-secret-test-secret-test-secret-test-secret",
        "adsignage.jwt.expiration-ms=3600000",
    ]
)
class AuthLoginIntegrationTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var mapper: ObjectMapper

    @Autowired
    lateinit var advertiserRepository: AdvertiserRepository

    @Autowired
    lateinit var jwtService: JwtService

    @BeforeEach
    fun reset() {
        advertiserRepository.deleteAll()
    }

    private fun seedAdvertiser(email: String, password: String): String {
        val signup = SignupRequest(email = email, password = password)
        mockMvc.perform(
            post("/api/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(signup))
        ).andExpect(status().isCreated)
        return advertiserRepository.findByEmail(email.lowercase()).orElseThrow().id
    }

    @Test
    fun `login returns 200 with signed JWT for valid credentials`() {
        val advertiserId = seedAdvertiser("owner@example.com", "StrongPass1")

        val req = LoginRequest(email = "owner@example.com", password = "StrongPass1")

        val response = mockMvc.perform(
            post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(req))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.accessToken").exists())
            .andExpect(jsonPath("$.tokenType").value("Bearer"))
            .andExpect(jsonPath("$.expiresInMs").value(3_600_000))
            .andExpect(jsonPath("$.advertiserId").value(advertiserId))
            .andExpect(jsonPath("$.email").value("owner@example.com"))
            .andReturn()

        val body = mapper.readTree(response.response.contentAsString)
        val token = body["accessToken"].asText()
        assertTrue(token.isNotBlank(), "accessToken must be non-empty")
        // JWT compact form is three base64url segments separated by dots.
        assertEquals(3, token.split(".").size, "JWT must have header.payload.signature segments")

        // Cryptographically verify the token round-trips and carries the
        // expected subject (advertiserId).
        val parsedSubject = jwtService.parseSubject(token)
        assertEquals(advertiserId, parsedSubject)
    }

    @Test
    fun `login is case-insensitive on the email`() {
        val advertiserId = seedAdvertiser("Mixed@Example.COM", "StrongPass1")

        val req = LoginRequest(email = "MIXED@example.com", password = "StrongPass1")

        mockMvc.perform(
            post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(req))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.advertiserId").value(advertiserId))
            .andExpect(jsonPath("$.email").value("mixed@example.com"))
    }

    @Test
    fun `login returns 401 when password is wrong`() {
        seedAdvertiser("owner@example.com", "StrongPass1")

        val req = LoginRequest(email = "owner@example.com", password = "WrongPass9")

        mockMvc.perform(
            post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(req))
        )
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.message").exists())
    }

    @Test
    fun `login returns 401 when email is not registered`() {
        // No seeded advertiser at all.
        val req = LoginRequest(email = "ghost@example.com", password = "StrongPass1")

        mockMvc.perform(
            post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(req))
        )
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.message").exists())
    }

    @Test
    fun `login rejects malformed payload with 400`() {
        val req = LoginRequest(email = "not-an-email", password = "")

        val result = mockMvc.perform(
            post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(req))
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.fieldErrors").exists())
            .andReturn()

        val body = mapper.readTree(result.response.contentAsString)
        assertNotNull(body["fieldErrors"]["email"])
        assertNotNull(body["fieldErrors"]["password"])
    }
}
