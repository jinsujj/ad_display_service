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
 * 인증 서비스.
 *
 * Sub-AC 2 범위: 회원가입 플로우 구현.
 *  - 유일성을 위해 이메일을 정규화(소문자 변환, trim).
 *  - 중복을 [DuplicateEmailException]으로 거부.
 *  - 주입된 [PasswordEncoder] (BCrypt)로 비밀번호 해시.
 *  - 새 [Advertiser]를 영속화하고 [SignupResponse]를 반환.
 *
 * Sub-AC 3 범위: 로그인 플로우 구현.
 *  - 정규화된 이메일로 광고주를 조회.
 *  - 제시된 비밀번호를 저장된 BCrypt 해시와 검증.
 *  - 성공 시 [JwtService]를 통해 서명된 JWT (HS256)를 반환.
 *  - 실패 시 [InvalidCredentialsException]을 던짐 — "그런 사용자 없음"과
 *    "비밀번호 틀림" 모두 동일한 예외를 사용하여 어떤 이메일이 등록되어
 *    있는지 누설하지 않음.
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
 * 회원가입 시도가 이미 등록된 이메일과 충돌할 때 던져짐.
 * 전역 예외 핸들러에 의해 HTTP 409로 매핑됨.
 */
class DuplicateEmailException(val email: String) :
    RuntimeException("Email already registered: $email")

/**
 * 로그인 시도가 실패할 때 던져짐 — 이메일이 등록되지 않았거나 비밀번호가
 * 일치하지 않을 때. 어떤 조건이 위반되었는지 API가 노출하지 않도록
 * 의도적으로 일반적임. 전역 예외 핸들러에 의해 HTTP 401로 매핑됨.
 */
class InvalidCredentialsException : RuntimeException("Invalid email or password")
