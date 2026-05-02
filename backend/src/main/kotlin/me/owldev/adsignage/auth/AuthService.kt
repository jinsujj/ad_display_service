package me.owldev.adsignage.auth

import me.owldev.adsignage.auth.dto.LoginRequest
import me.owldev.adsignage.auth.dto.LoginResponse
import me.owldev.adsignage.auth.dto.SignupRequest
import me.owldev.adsignage.auth.dto.SignupResponse
import me.owldev.adsignage.auth.jwt.JwtService
import me.owldev.adsignage.domain.advertiser.Advertiser
import me.owldev.adsignage.domain.advertiser.AdvertiserRepository
import org.slf4j.LoggerFactory
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Authentication service.
 *
 * Sub-AC 2 scope: implement the signup flow.
 *  - Normalises the email (lower-case, trim) for uniqueness.
 *  - Rejects duplicates with [DuplicateEmailException].
 *  - Hashes the password with the injected [PasswordEncoder] (BCrypt).
 *  - Persists a new [Advertiser] and returns a [SignupResponse].
 *
 * Sub-AC 3 scope: implement the login flow.
 *  - Looks up the advertiser by normalised email.
 *  - Verifies the supplied password against the stored BCrypt hash.
 *  - Returns a signed JWT (HS256) on success via [JwtService].
 *  - Throws [InvalidCredentialsException] on any failure — the same
 *    exception is used for both "no such user" and "wrong password" so we
 *    don't leak which emails are registered.
 */
@Service
class AuthService(
    private val advertiserRepository: AdvertiserRepository,
    private val passwordEncoder: PasswordEncoder,
    private val jwtService: JwtService,
) {

    private val log = LoggerFactory.getLogger(AuthService::class.java)

    @Transactional
    fun signup(request: SignupRequest): SignupResponse {
        val normalisedEmail = request.email.trim().lowercase()

        if (advertiserRepository.existsByEmail(normalisedEmail)) {
            log.info("signup rejected: email already registered ({})", normalisedEmail)
            throw DuplicateEmailException(normalisedEmail)
        }

        val advertiser = Advertiser(
            email = normalisedEmail,
            passwordHash = passwordEncoder.encode(request.password),
        )
        val saved = advertiserRepository.save(advertiser)
        log.info("advertiser registered id={} email={}", saved.id, saved.email)

        return SignupResponse(
            advertiserId = saved.id,
            email = saved.email,
            createdAt = saved.createdAt,
        )
    }

    @Transactional(readOnly = true)
    fun login(request: LoginRequest): LoginResponse {
        val normalisedEmail = request.email.trim().lowercase()

        val advertiser = advertiserRepository.findByEmail(normalisedEmail)
            .orElseThrow {
                log.info("login rejected: unknown email ({})", normalisedEmail)
                InvalidCredentialsException()
            }

        if (!passwordEncoder.matches(request.password, advertiser.passwordHash)) {
            log.info("login rejected: bad password for ({})", normalisedEmail)
            throw InvalidCredentialsException()
        }

        val issued = jwtService.issueToken(
            advertiserId = advertiser.id,
            email = advertiser.email,
        )
        log.info("login success advertiserId={} email={}", advertiser.id, advertiser.email)

        return LoginResponse(
            accessToken = issued.token,
            expiresInMs = issued.expiresInMs,
            advertiserId = advertiser.id,
            email = advertiser.email,
        )
    }
}

/**
 * Thrown when a signup attempt collides with an already-registered email.
 * Mapped to HTTP 409 by the global exception handler.
 */
class DuplicateEmailException(val email: String) :
    RuntimeException("Email already registered: $email")

/**
 * Thrown when a login attempt fails — either the email is not registered or
 * the password does not match. Intentionally generic so the API does not
 * disclose which condition was violated. Mapped to HTTP 401 by the global
 * exception handler.
 */
class InvalidCredentialsException : RuntimeException("Invalid email or password")
