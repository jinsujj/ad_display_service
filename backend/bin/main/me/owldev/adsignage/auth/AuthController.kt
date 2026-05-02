package me.owldev.adsignage.auth

import jakarta.validation.Valid
import me.owldev.adsignage.auth.dto.LoginRequest
import me.owldev.adsignage.auth.dto.LoginResponse
import me.owldev.adsignage.auth.dto.SignupRequest
import me.owldev.adsignage.auth.dto.SignupResponse
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 인증 REST 엔드포인트.
 *
 * Sub-AC 2: `POST /api/auth/signup`을 노출.
 * Sub-AC 3: `POST /api/auth/login`을 노출(자격증명을 검증하고 서명된
 *           HS256 JWT 액세스 토큰을 반환).
 */
@RestController
@RequestMapping("/api/auth")
class AuthController(
    private val authService: AuthService,
) {

    @PostMapping("/signup")
    fun signup(@Valid @RequestBody request: SignupRequest): ResponseEntity<SignupResponse> {
        val response = authService.signup(request)
        return ResponseEntity.status(HttpStatus.CREATED).body(response)
    }

    @PostMapping("/login")
    fun login(@Valid @RequestBody request: LoginRequest): ResponseEntity<LoginResponse> {
        val response = authService.login(request)
        return ResponseEntity.ok(response)
    }
}
