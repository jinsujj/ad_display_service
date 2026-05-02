package me.owldev.adsignage.auth.dto

/**
 * `POST /api/auth/login`의 응답 본문.
 *
 * 서명된 JWT 액세스 토큰(HS256)과 해석된 광고주 신원을 반환.
 * `tokenType`은 "Bearer"로 고정되어 있어 클라이언트가 추가 분기 로직 없이
 * 표준 `Authorization: Bearer <accessToken>` 헤더를 사용할 수 있음.
 *
 * 온톨로지 매핑:
 *  - [advertiserId] → advertiser_id (JWT `sub` 클레임으로도 임베드)
 *  - [email]        → advertiser_email
 */
data class LoginResponse(
    val accessToken: String,
    val tokenType: String = "Bearer",
    val expiresInMs: Long,
    val advertiserId: String,
    val email: String,
)
