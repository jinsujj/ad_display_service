package me.owldev.adsignage.domain.playevent

import me.owldev.adsignage.domain.playevent.dto.DailyPlayCountResponse
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.time.ZoneId

/**
 * AC 20203 Sub-AC 3 — read-side endpoint that surfaces the server's
 * authoritative daily FINISHED count for an ad.
 *
 * Wire contract:
 *
 *   GET /api/ads/{adId}/play-events/daily-count
 *
 *   ⇒ 200 OK
 *      {
 *        "adId":   "<uuid>",
 *        "date":   "YYYY-MM-DD",
 *        "zoneId": "Asia/Seoul",
 *        "count":  42
 *      }
 *
 * **Why mounted under `/api/ads/{adId}/...`** (rather than under the
 * device path that hosts the POST counterpart):
 *
 *  - The daily cap is **campaign-wide**, not per-device. An advertiser
 *    bought "200 plays/day" — across every screen running the ad. Mount-
 *    ing the read under `/api/ads` makes the resource lineage match the
 *    semantics: it's a property of the *ad*, not of any single device.
 *  - Aligns with `/api/ads/{id}/schedule` — the sibling admin route that
 *    set the cap in the first place. An operator opens the ad, sees the
 *    cap they configured, and the count fired off the same hierarchy.
 *  - Inherits the existing `/api/ads/{ANY}` security rule
 *    (`.authenticated()` in [me.owldev.adsignage.config.SecurityConfig])
 *    so this endpoint is JWT-gated by default — no security carve-out
 *    needed. The auth-and-isolation pass that adds advertiser-ownership
 *    filtering to ad CRUD will pick this route up for free.
 *
 * **Why no `?date=` query parameter** (yet): the player and the dashboard
 * both ask for "today" — there is no historical-replay surface in the
 * current UI. Adding date arithmetic without a UI consumer is wasted
 * surface area; the helper inside [PlayEventService.dailyFinishedCount]
 * already accepts an [Instant], so a `?date=YYYY-MM-DD` parameter can
 * land in one method when the dashboard grows a date picker.
 *
 * **Why an unknown adId returns 200 with `count=0`** rather than 404: the
 * dashboard polls this endpoint immediately after creating an ad — before
 * any plays have landed — and surfacing a 404 there would force a
 * "still loading…" state for newly-created ads that just don't have data
 * yet. The semantic "no plays for that id" is encoded as a zero count,
 * matching how `web/lib/dailyCount.ts` treats a missing key.
 */
@RestController
@RequestMapping("/api/ads/{adId}/play-events")
class AdPlayCountController(
    private val playEventService: PlayEventService,
    @Value("\${adsignage.daily-count.zone-id:Asia/Seoul}")
    private val zoneIdProperty: String,
) {

    private val log = LoggerFactory.getLogger(AdPlayCountController::class.java)

    /**
     * Resolved at construction so the response can echo the same string
     * the service used internally — a misconfigured zone fails fast at
     * startup rather than silently miscounting.
     */
    private val zoneId: ZoneId = ZoneId.of(zoneIdProperty)

    /**
     * Returns the FINISHED-event count for [adId] on today's calendar day
     * in the configured zone. The `date` and `zoneId` echoed in the body
     * let a client detect a server-vs-client zone drift before
     * misrendering the cap.
     */
    @GetMapping(
        "/daily-count",
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    fun getDailyCount(
        @PathVariable("adId") adId: String,
    ): ResponseEntity<DailyPlayCountResponse> {
        val now = Instant.now()
        val count = playEventService.dailyFinishedCount(adId = adId, at = now)
        val date = now.atZone(zoneId).toLocalDate().toString() // ISO YYYY-MM-DD
        log.info(
            "GET /api/ads/{}/play-events/daily-count → date={} zone={} count={}",
            adId, date, zoneId.id, count,
        )
        return ResponseEntity.ok(
            DailyPlayCountResponse(
                adId = adId,
                date = date,
                zoneId = zoneId.id,
                count = count,
            ),
        )
    }
}
