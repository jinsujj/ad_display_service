package me.owldev.adsignage.auth.dto

/**
 * Response body for `POST /api/auth/login`.
 *
 * Returns a signed JWT access token (HS256) plus the resolved advertiser
 * identity. `tokenType` is fixed to "Bearer" so clients can use the
 * standard `Authorization: Bearer <accessToken>` header without extra
 * branching logic.
 *
 * Ontology mapping:
 *  - [advertiserId] → advertiser_id (also embedded as the JWT `sub` claim)
 *  - [email]        → advertiser_email
 */
data class LoginResponse(
    val accessToken: String,
    val tokenType: String = "Bearer",
    val expiresInMs: Long,
    val advertiserId: String,
    val email: String,
)
