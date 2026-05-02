package me.owldev.adsignage.auth.dto

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

/**
 * Request body for `POST /api/auth/signup`.
 *
 * Ontology mapping:
 *  - [email]    → advertiser_email
 *  - [password] → advertiser_password_hash (after BCrypt hashing in service)
 */
data class SignupRequest(
    @field:NotBlank(message = "email must not be blank")
    @field:Email(message = "email must be a valid email address")
    @field:Size(max = 255, message = "email must be at most 255 characters")
    val email: String,

    @field:NotBlank(message = "password must not be blank")
    @field:Size(min = 8, max = 100, message = "password must be between 8 and 100 characters")
    val password: String,
)
