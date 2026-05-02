package me.owldev.adsignage.domain.ad

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.Optional

/**
 * Spring Data JPA repository for [Ad].
 *
 * Lookups intentionally surface an "and advertiserId" variant for every
 * single-row read used by mutating endpoints. The auth-and-isolation contract
 * (AC 4) is that an advertiser can only see/modify their own ads — pushing
 * the predicate into the query (rather than fetching by id and then comparing
 * in Kotlin) ensures cross-advertiser id-guessing returns `Optional.empty()`
 * and hands a 404 to the caller. There is no path through the controller that
 * leaks "this id exists but isn't yours" vs. "this id doesn't exist" — both
 * collapse to the same not-found shape.
 *
 * Hot paths supported:
 *  - "fetch this single ad, but only if it belongs to me"  → [findByIdAndAdvertiserId]
 *  - "list all my ads, newest first" (admin dashboard)     → [findAllByAdvertiserIdOrderByCreatedAtDesc]
 *  - playlist computation joins ads → videos by filename   → handled by the
 *    generic [findAll] / future per-restaurant queries; not needed for the
 *    schedule-update endpoint specifically.
 */
@Repository
interface AdRepository : JpaRepository<Ad, String> {

    /**
     * Returns the [Ad] whose id is [id] **and** whose [Ad.advertiserId] is
     * [advertiserId]. Empty if either condition fails — the controller maps
     * empty to 404, never leaking the existence of another advertiser's ad.
     */
    fun findByIdAndAdvertiserId(id: String, advertiserId: String): Optional<Ad>

    /**
     * Returns every ad owned by [advertiserId], newest first. Backs the
     * admin dashboard's "your ads" listing — the
     * `idx_ads_advertiser_id` index served by V40 covers the WHERE; the
     * ORDER BY uses the secondary `idx_ads_created_at` (or a sort step in
     * the planner if it prefers).
     */
    fun findAllByAdvertiserIdOrderByCreatedAtDesc(advertiserId: String): List<Ad>
}
