package me.owldev.adsignage.auth.dto

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

/**
 * Request body for `POST /api/auth/login`.
 *
 * Ontology mapping:
 *  - [email]    → advertiser_email
 *  - [password] → verified against advertiser_password_hash
 *
 * Note: validation is intentionally lenient compared to signup — we still
 * require the fields to be present and well-formed, but we do not enforce
 * the same minimum length on `password` here. The credentials get checked
 * against the BCrypt hash anyway, and a stricter rule would just leak the
 * signup policy to attackers.
 */
data class LoginRequest(
    @field:NotBlank(message = "email must not be blank")
    @field:Email(message = "email must be a valid email address")
    @field:Size(max = 255, message = "email must be at most 255 characters")
    val email: String,

    @field:NotBlank(message = "password must not be blank")
    @field:Size(max = 100, message = "password must be at most 100 characters")
    val password: String,
)
