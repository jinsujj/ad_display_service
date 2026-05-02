package me.owldev.adsignage.auth

import com.fasterxml.jackson.databind.ObjectMapper
import me.owldev.adsignage.auth.dto.SignupRequest
import me.owldev.adsignage.domain.advertiser.AdvertiserRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Sub-AC 2 verification: POST /api/auth/signup
 *  - 201 on success, returns advertiserId + email + createdAt
 *  - password is BCrypt-hashed (not stored in plain text)
 *  - 400 on invalid payload (bad email / short password)
 *  - 409 on duplicate email
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(
    properties = [
        // Sub-AC 2 owns only the advertisers table; sibling sub-ACs may add
        // migrations referencing tables that don't exist yet. We bypass
        // Flyway here and let Hibernate create the schema for the entities
        // present on the classpath, isolating this test from sibling churn.
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        // In-memory DB per test run so we never collide with the dev H2 file.
        "spring.datasource.url=jdbc:h2:mem:auth-signup-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    ]
)
class AuthSignupIntegrationTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var mapper: ObjectMapper

    @Autowired
    lateinit var advertiserRepository: AdvertiserRepository

    @Autowired
    lateinit var passwordEncoder: PasswordEncoder

    @BeforeEach
    fun reset() {
        advertiserRepository.deleteAll()
    }

    @Test
    fun `signup creates advertiser with bcrypt-hashed password`() {
        val req = SignupRequest(email = "owner@example.com", password = "StrongPass1")

        mockMvc.perform(
            post("/api/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(req))
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.advertiserId").exists())
            .andExpect(jsonPath("$.email").value("owner@example.com"))
            .andExpect(jsonPath("$.createdAt").exists())

        val saved = advertiserRepository.findByEmail("owner@example.com").orElse(null)
        assertNotNull(saved, "advertiser should be persisted")
        assertNotEquals("StrongPass1", saved.passwordHash, "password must NOT be stored in plain text")
        assertTrue(saved.passwordHash.startsWith("\$2"), "password hash should be a BCrypt hash")
        assertTrue(passwordEncoder.matches("StrongPass1", saved.passwordHash))
    }

    @Test
    fun `signup normalises email to lowercase`() {
        // Note: `@Email` validation rejects surrounding whitespace, so we test
        // case-normalisation only — the service-level trim() still acts as a
        // belt-and-braces safeguard for any non-validated call sites.
        val req = SignupRequest(email = "Mixed@Example.COM", password = "StrongPass1")

        mockMvc.perform(
            post("/api/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(req))
        ).andExpect(status().isCreated)

        assertNotNull(advertiserRepository.findByEmail("mixed@example.com").orElse(null))
    }

    @Test
    fun `signup rejects invalid email and short password with 400`() {
        val req = SignupRequest(email = "not-an-email", password = "short")

        mockMvc.perform(
            post("/api/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(req))
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.fieldErrors.email").exists())
            .andExpect(jsonPath("$.fieldErrors.password").exists())
    }

    @Test
    fun `signup rejects blank email and blank password with 400`() {
        val body = """{"email":"","password":""}"""

        mockMvc.perform(
            post("/api/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.fieldErrors.email").exists())
            .andExpect(jsonPath("$.fieldErrors.password").exists())
    }

    @Test
    fun `signup rejects duplicate email with 409`() {
        val req = SignupRequest(email = "dup@example.com", password = "StrongPass1")
        val json = mapper.writeValueAsString(req)

        mockMvc.perform(
            post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content(json)
        ).andExpect(status().isCreated)

        mockMvc.perform(
            post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content(json)
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.message").exists())
    }
}
