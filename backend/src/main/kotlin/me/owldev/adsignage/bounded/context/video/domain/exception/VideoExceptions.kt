package me.owldev.adsignage.bounded.context.video.domain.exception

/**
 * 영상 업로드 실패의 sealed 계층. 각 하위 클래스는 의미상 올바른 HTTP
 * 상태(400/413/415)에 매핑됨.
 */
sealed class VideoUploadException(message: String) : RuntimeException(message)

/**
 * multipart 업로드에 바이트가 없거나 누락. HTTP 400.
 */
class EmptyVideoUploadException :
    VideoUploadException("Uploaded video is empty")

/**
 * multipart 업로드가 사용 가능한 원본 파일명 없이 도착. HTTP 400.
 */
class MissingVideoFilenameException(val received: String?) :
    VideoUploadException(
        "Uploaded video has no usable original filename" +
            (received?.let { " (received: '$it')" } ?: ""),
    )

/**
 * 보고된 `Content-Type`이 설정된 허용 목록에 없음. HTTP 415.
 */
class InvalidVideoMimeTypeException(
    val actual: String?,
    val allowed: Collection<String>,
) : VideoUploadException(
    "Unsupported video MIME type: '${actual ?: "<missing>"}' (allowed: $allowed)",
)

/**
 * 업로드가 설정된 서비스 레벨 크기 한도를 초과. HTTP 413.
 */
class VideoTooLargeException(
    val actualBytes: Long,
    val maxBytes: Long,
) : VideoUploadException(
    "Video size $actualBytes bytes exceeds the maximum allowed $maxBytes bytes",
)

/**
 * 영상 스트리밍 엔드포인트가 발생시키는 타입화된 예외 (404/416).
 */
sealed class VideoStreamingException(message: String) : RuntimeException(message)

/**
 * 요청된 파일명이 어떤 영속화된 [me.owldev.adsignage.bounded.context.video.domain.model.Video]
 * 행과도 일치하지 않거나, 행이 삽입된 이후 디스크 상의 바이트가 제거된 경우. HTTP 404.
 */
class VideoNotFoundException(val filename: String) :
    VideoStreamingException("No video found for filename '$filename'")

/**
 * 클라이언트가 구문상 유효한 `Range` 헤더를 보냈으나 요청된 바이트 윈도우를
 * 충족할 수 없는 경우. HTTP 416. RFC 7233 §4.4에 따라 응답은 `Content-Range:
 * bytes * /completeLength` 헤더를 반드시 포함.
 */
class UnsatisfiableRangeException(
    val rangeHeader: String,
    val fileSize: Long,
) : VideoStreamingException(
    "Range '$rangeHeader' cannot be satisfied for file of size $fileSize bytes",
)
