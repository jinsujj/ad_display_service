package me.owldev.adsignage.auth.dto

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

/**
 * `POST /api/auth/login`의 요청 본문.
 *
 * 온톨로지 매핑:
 *  - [email]    → advertiser_email
 *  - [password] → advertiser_password_hash와 대조 검증
 *
 * 노트: 검증은 회원가입에 비해 의도적으로 관대함 — 필드가 존재하고 형식이
 * 올바르도록 여전히 요구하지만, 여기서는 `password`에 동일한 최소 길이를
 * 강제하지 않음. 어차피 자격증명은 BCrypt 해시와 대조되며, 더 엄격한
 * 규칙은 공격자에게 회원가입 정책만 노출시킬 뿐.
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
