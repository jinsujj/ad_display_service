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
 * Auth REST endpoints.
 *
 * Sub-AC 2: exposes `POST /api/auth/signup`.
 * Sub-AC 3: exposes `POST /api/auth/login` (verifies credentials and returns
 *           a signed HS256 JWT access token).
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
