package me.owldev.adsignage.domain.ad

/**
 * Thrown when an [AdService] operation references an ad_id that either does
 * not exist *or* exists but is not owned by the calling advertiser. Mapped
 * to HTTP 404 by [me.owldev.adsignage.web.GlobalExceptionHandler].
 *
 * The "not yours" case is intentionally collapsed into the "not found" case
 * — the service never tells one advertiser whether a different advertiser's
 * ad id exists, satisfying the AC 4 auth-and-isolation contract.
 */
class AdNotFoundException(val adId: String) :
    RuntimeException("Ad not found: $adId")

/**
 * Thrown when a schedule payload passes bean-validation field-level checks
 * but fails a *cross-field* invariant — specifically, `endTime` must be
 * strictly after `startTime`. Bean Validation's `@AssertTrue` on a derived
 * property would also work, but raising a typed exception keeps the
 * cross-field rule discoverable from the service signature and lets the
 * GlobalExceptionHandler surface a clean field-error map identical to the
 * one produced by `MethodArgumentNotValidException`.
 *
 * Mapped to HTTP 400 with a `fieldErrors` map keyed by the offending
 * field (e.g. `endTime`).
 */
class InvalidScheduleException(
    val fieldErrors: Map<String, String>,
    message: String = "Schedule validation failed",
) : RuntimeException(message)
