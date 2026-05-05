package me.owldev.adsignage.bounded.context.video.adapter.`in`.api

import me.owldev.adsignage.auth.jwt.AdvertiserPrincipal
import me.owldev.adsignage.bounded.context.video.application.service.VideoListService
import me.owldev.adsignage.bounded.context.video.application.service.VideoUploadService
import me.owldev.adsignage.bounded.context.video.domain.dto.VideoResponse
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
 * 광고주가 업로드한 비디오 자산을 위한 REST 엔드포인트.
 *
 *  - `GET /api/videos` — 호출 광고주의 업로드된 비디오 목록 (최신 순).
 *  - `POST /api/videos` — multipart/form-data 업로드.
 *
 * 둘 다 JWT 필요. 스트리밍 엔드포인트(`GET /api/videos/{filename}`)는 형제
 * [VideoStreamingController] 가 서비스하며 SecurityConfig 에서 공개로 유지.
 */
@RestController
@RequestMapping("/api/videos")
class VideoController(
    private val videoUploadService: VideoUploadService,
    private val videoListService: VideoListService,
) {

    private val log = LoggerFactory.getLogger(VideoController::class.java)

    @GetMapping(produces = [MediaType.APPLICATION_JSON_VALUE])
    fun list(
        @AuthenticationPrincipal principal: AdvertiserPrincipal,
    ): ResponseEntity<List<VideoResponse>> {
        val videos = videoListService.listOwned(principal.advertiserId)
        log.info(
            "GET /api/videos advertiserId={} returning {} video(s)",
            principal.advertiserId, videos.size,
        )
        return ResponseEntity.ok(videos.map(VideoResponse::from))
    }

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
