package me.owldev.adsignage.auth.dto

import java.time.Instant

/**
 * `POST /api/auth/signup`의 응답 본문.
 *
 * 새로 생성된 광고주 신원을 반환. Sub-AC 2에서는 JWT를 의도적으로 발급하지
 * 않음 — 클라이언트는 토큰을 얻기 위해 `POST /api/auth/login`을 호출해야 함.
 * [advertiserId]를 반환하면 관리자 웹 플로우에 유용하면서도 계약이 최소로
 * 유지됨.
 */
data class SignupResponse(
    val advertiserId: String,
    val email: String,
    val createdAt: Instant,
)
