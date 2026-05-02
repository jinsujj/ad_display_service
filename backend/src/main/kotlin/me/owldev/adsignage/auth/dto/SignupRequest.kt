package me.owldev.adsignage.auth.dto

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

/**
 * `POST /api/auth/signup`의 요청 본문.
 *
 * 온톨로지 매핑:
 *  - [email]    → advertiser_email
 *  - [password] → advertiser_password_hash (서비스에서 BCrypt 해싱 후)
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
