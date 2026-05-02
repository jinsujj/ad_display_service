package me.owldev.adsignage.domain.video

import me.owldev.adsignage.auth.jwt.AdvertiserPrincipal
import me.owldev.adsignage.domain.video.dto.VideoResponse
import me.owldev.adsignage.domain.video.upload.EmptyVideoUploadException
import me.owldev.adsignage.domain.video.upload.VideoUploadService
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

/**
 * REST endpoints for advertiser-uploaded video assets.
 *
 * Sub-AC 4 scope — `POST /api/videos`:
 *  - **Multipart/form-data only.** The endpoint advertises
 *    `consumes = MULTIPART_FORM_DATA_VALUE`; any other content type is
 *    rejected by Spring with 415 before it reaches this controller.
 *  - **`file` part is required.** The multipart `name` is `file` to match the
 *    convention used in [VideoUploadServiceTest] and the admin UI fetch call.
 *    A missing part is mapped to 400 (Spring's
 *    `MissingServletRequestPartException` defaults to 400) and an empty body
 *    is mapped to 400 by [me.owldev.adsignage.web.GlobalExceptionHandler]
 *    via [EmptyVideoUploadException].
 *  - **Validation lives in the service.** [VideoUploadService.upload] runs
 *    MIME and size checks *before* writing any bytes to disk and raises
 *    typed exceptions ([me.owldev.adsignage.domain.video.upload.VideoUploadException]
 *    subclasses) that the global exception handler maps to 400 / 413 / 415.
 *  - **Storage + persistence is delegated.** The service composes
 *    [me.owldev.adsignage.domain.video.storage.VideoStorageService] (writes
 *    bytes) with [VideoRepository] (writes the row) inside a single
 *    transaction — the controller is only responsible for the HTTP shape.
 *  - **Returns 201 Created with [VideoResponse]**. The DTO carries the
 *    server-generated id, on-disk filename, MIME type, size, canonical
 *    streaming URL, and upload timestamp — everything the admin UI needs to
 *    render the new row without re-fetching.
 *
 * AC 4 — auth-and-isolation pass:
 *  - Both `POST /api/videos` and `GET /api/videos` require an authenticated
 *    advertiser. The JWT's `sub` claim is materialised as
 *    [AdvertiserPrincipal] by
 *    [me.owldev.adsignage.auth.jwt.JwtAuthenticationFilter] and injected
 *    into both methods via Spring Security's `@AuthenticationPrincipal`
 *    argument resolver — that's the canonical way to read the verified
 *    identity in a controller without round-tripping through
 *    `SecurityContextHolder`.
 *  - The streaming endpoint
 *    (`GET /api/videos/{filename}`, served by a sibling controller) stays
 *    public so the unauthenticated player page can fetch MP4 bytes; only
 *    the *parent* `/api/videos` list is locked down, via the tightened
 *    `permitAll()` ant-pattern in
 *    [me.owldev.adsignage.config.SecurityConfig] (`/api/videos/{star}` rather
 *    than `/api/videos/{star}{star}`; literal asterisks intentionally
 *    described in words because Kotlin's nestable block comments treat
 *    a literal `/{star}{star}` as opening a nested KDoc).
 *  - Ownership isolation is enforced at the repository call site:
 *    [VideoRepository.findAllByAdvertiserIdOrderByUploadedAtDesc] only
 *    returns rows whose `advertiser_id` equals the JWT-asserted id.
 *    Cross-advertiser id guessing is impossible because the predicate is
 *    bound to the verified principal, not to a request parameter.
 */
@RestController
@RequestMapping("/api/videos")
class VideoController(
    private val videoUploadService: VideoUploadService,
    private val videoRepository: VideoRepository,
) {

    private val log = LoggerFactory.getLogger(VideoController::class.java)

    /**
     * Lists the *signed-in advertiser's* uploaded videos, newest first.
     *
     * Authorisation contract (AC 4):
     *  - Spring Security has already rejected the request before it reaches
     *    this method if the JWT is missing/invalid — the
     *    [AdvertiserPrincipal] parameter is therefore guaranteed non-null
     *    when the handler runs. We declare it as non-nullable so a
     *    misconfigured `permitAll()` rule that lets an anonymous request
     *    through fails fast (NPE / 500) rather than returning *every*
     *    advertiser's videos.
     *
     * Sort order:
     *  - `uploaded_at DESC` — operators who just hit "Upload" expect to
     *    see their new row at the top. The composite index added in V31
     *    `(advertiser_id, uploaded_at)` lets the planner serve both the
     *    WHERE and ORDER BY without a sort step.
     *
     * Empty-state contract:
     *  - Returns `200 OK` with an empty JSON array when this advertiser
     *    has uploaded no videos yet. The admin UI relies on `[]` to render
     *    its empty state rather than treating "no results" as an error.
     */
    @GetMapping(produces = [MediaType.APPLICATION_JSON_VALUE])
    fun list(
        @AuthenticationPrincipal principal: AdvertiserPrincipal,
    ): ResponseEntity<List<VideoResponse>> {
        val videos = videoRepository
            .findAllByAdvertiserIdOrderByUploadedAtDesc(principal.advertiserId)
        log.info(
            "GET /api/videos advertiserId={} returning {} video(s)",
            principal.advertiserId, videos.size,
        )
        return ResponseEntity.ok(videos.map(VideoResponse::from))
    }

    /**
     * Accepts a single MP4 upload as multipart/form-data and returns the
     * persisted [Video] as a [VideoResponse].
     *
     * Why `@RequestParam` instead of `@RequestPart`:
     *  - For a plain file part, `@RequestParam("file") MultipartFile` is the
     *    idiomatic Spring binding and produces the cleanest error message
     *    when the part is missing (Spring's
     *    `MissingServletRequestParameterException` → 400). `@RequestPart`
     *    is meant for parts that need JSON conversion (e.g. a metadata
     *    object alongside the file), which we don't have.
     *  - The multipart name `file` mirrors the existing service test
     *    fixtures (`MockMultipartFile("file", …)`) so the admin UI's
     *    `FormData.append("file", blob)` call works without a server-side
     *    rename.
     *
     * Ownership stamping (AC 4):
     *  - The verified [AdvertiserPrincipal] is forwarded to
     *    [VideoUploadService.upload] so the persisted row carries the
     *    JWT-asserted owner id. Doing the stamp at the service boundary
     *    (rather than letting the controller build the `Video` directly)
     *    prevents a future code path from forgetting to set the column —
     *    the service signature requires the id.
     */
    @PostMapping(consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun upload(
        @RequestParam("file") file: MultipartFile,
        @AuthenticationPrincipal principal: AdvertiserPrincipal,
    ): ResponseEntity<VideoResponse> {
        log.info(
            "POST /api/videos advertiserId={} originalName='{}' contentType={} size={}",
            principal.advertiserId, file.originalFilename, file.contentType, file.size,
        )
        val saved = videoUploadService.upload(file, principal.advertiserId)
        log.info(
            "video upload succeeded id={} filename={} advertiserId={}",
            saved.id, saved.filename, saved.advertiserId,
        )
        return ResponseEntity
            .status(HttpStatus.CREATED)
            .body(VideoResponse.from(saved))
    }
}
