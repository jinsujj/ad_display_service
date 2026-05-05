package me.owldev.adsignage.bounded.context.video.adapter.`in`.api

import me.owldev.adsignage.bounded.context.video.domain.exception.EmptyVideoUploadException
import me.owldev.adsignage.bounded.context.video.domain.exception.InvalidVideoMimeTypeException
import me.owldev.adsignage.bounded.context.video.domain.exception.MissingVideoFilenameException
import me.owldev.adsignage.bounded.context.video.domain.exception.UnsatisfiableRangeException
import me.owldev.adsignage.bounded.context.video.domain.exception.VideoNotFoundException
import me.owldev.adsignage.bounded.context.video.domain.exception.VideoTooLargeException
import me.owldev.adsignage.common.web.ApiError
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * Video 컨텍스트 도메인 예외 매핑.
 *  - 빈 업로드 / 파일명 누락 → 400
 *  - MIME 미지원 → 415
 *  - 크기 초과(서비스 레벨) → 413
 *  - 비디오/Range 오류 → 404 / 416
 */
@RestControllerAdvice
class VideoExceptionHandler {

    /**
     * 빈 multipart 본문과 누락된 파일명은 400 Bad Request.
     */
    @ExceptionHandler(
        EmptyVideoUploadException::class,
        MissingVideoFilenameException::class,
    )
    fun handleVideoUploadBadRequest(ex: RuntimeException): ResponseEntity<ApiError> {
        val body = ApiError(
            status = HttpStatus.BAD_REQUEST.value(),
            error = "Bad Request",
            message = ex.message ?: "Invalid video upload",
        )
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body)
    }

    /**
     * MP4 가 아닌 (또는 `Content-Type` 이 없는) 업로드 → 415 Unsupported Media Type.
     */
    @ExceptionHandler(InvalidVideoMimeTypeException::class)
    fun handleInvalidVideoMimeType(ex: InvalidVideoMimeTypeException): ResponseEntity<ApiError> {
        val body = ApiError(
            status = HttpStatus.UNSUPPORTED_MEDIA_TYPE.value(),
            error = "Unsupported Media Type",
            message = ex.message ?: "Unsupported video MIME type",
        )
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).body(body)
    }

    /**
     * 서비스 레벨 크기 초과(디스크 닿기 전 거절) → 413 Payload Too Large.
     * 컨테이너 수준 [org.springframework.web.multipart.MaxUploadSizeExceededException]
     * 은 CommonExceptionHandler 가 같은 코드로 처리.
     */
    @ExceptionHandler(VideoTooLargeException::class)
    fun handleVideoTooLarge(ex: VideoTooLargeException): ResponseEntity<ApiError> {
        val body = ApiError(
            status = HttpStatus.PAYLOAD_TOO_LARGE.value(),
            error = "Payload Too Large",
            message = ex.message ?: "Uploaded video exceeds the size limit",
        )
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(body)
    }

    /**
     * 스트리밍 엔드포인트가 알 수 없는 파일명을 받거나 디스크 상의 바이트가
     * 사라진 경우 — 두 케이스 모두 플레이어 관점에서 404.
     */
    @ExceptionHandler(VideoNotFoundException::class)
    fun handleVideoNotFound(ex: VideoNotFoundException): ResponseEntity<ApiError> {
        val body = ApiError(
            status = HttpStatus.NOT_FOUND.value(),
            error = "Not Found",
            message = ex.message ?: "Video not found",
        )
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body)
    }

    /**
     * AC 14: 클라이언트가 대상 파일에 대해 만족시킬 수 없는 `Range` 헤더를 보냄 →
     * 416. RFC 7233 §4.4 가 클라이언트가 유효 range 를 재발행할 수 있도록
     * `Content-Range: bytes * /size` 헤더를 의무화.
     */
    @ExceptionHandler(UnsatisfiableRangeException::class)
    fun handleUnsatisfiableRange(ex: UnsatisfiableRangeException): ResponseEntity<ApiError> {
        val body = ApiError(
            status = HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE.value(),
            error = "Range Not Satisfiable",
            message = ex.message ?: "Range not satisfiable",
        )
        return ResponseEntity
            .status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
            .header(HttpHeaders.CONTENT_RANGE, "bytes */${ex.fileSize}")
            .body(body)
    }
}
