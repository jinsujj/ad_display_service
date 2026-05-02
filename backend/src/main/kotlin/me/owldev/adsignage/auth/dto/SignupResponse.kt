package me.owldev.adsignage.auth.dto

import java.time.Instant

/**
 * Response body for `POST /api/auth/signup`.
 *
 * Returns the newly created advertiser identity. A JWT is intentionally NOT
 * issued here in Sub-AC 2 — clients should call `POST /api/auth/login` to
 * obtain a token. Returning [advertiserId] keeps the contract minimal yet
 * useful for the admin web flow.
 */
data class SignupResponse(
    val advertiserId: String,
    val email: String,
    val createdAt: Instant,
)
