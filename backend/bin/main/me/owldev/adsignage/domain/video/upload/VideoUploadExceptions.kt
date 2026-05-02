package me.owldev.adsignage.domain.video.upload

/**
 * [VideoUploadService]가 잘못된 업로드에 대해 발생시키는 타입화된 예외.
 *
 * Sub-AC 3은 하위 레벨
 * [me.owldev.adsignage.domain.video.storage.VideoStorageService]가 파일시스템
 * 모양 문제에 사용하는 `IllegalArgumentException` 대신 *타입화된* 예외를
 * 명시적으로 요구한다. 분리하면 API 레이어가 예외 메시지를 문자열 매칭
 * 하지 않고 "클라이언트가 잘못된 콘텐츠 타입을 보냄"(415)과 "클라이언트가
 * 너무 큰 파일을 보냄"(413)을 구분할 수 있고, 업로드 파이프라인이 디스크에
 * 단 1바이트도 쓰기 *전에* 잘못된 요청을 거절할 수 있다.
 *
 * 모든 구체 하위 클래스는 sealed [VideoUploadException]을 상속하므로
 * `is VideoUploadException`에 대한 단일 `when`이
 * [me.owldev.adsignage.web.GlobalExceptionHandler]에서 HTTP 상태로 펼쳐질 수
 * 있다.
 */
sealed class VideoUploadException(message: String) : RuntimeException(message)

/**
 * multipart 업로드에 바이트가 없거나(또는 완전히 누락됨). HTTP 400 Bad
 * Request로 매핑.
 */
class EmptyVideoUploadException :
    VideoUploadException("Uploaded video is empty")

/**
 * multipart 업로드가 사용 가능한 원본 파일명 없이 도착함 — 보통 빈
 * `filename` 파라미터이거나 leaf-name 정규화 후 비는 경로. HTTP 400 Bad
 * Request로 매핑.
 */
class MissingVideoFilenameException(val received: String?) :
    VideoUploadException(
        "Uploaded video has no usable original filename" +
            (received?.let { " (received: '$it')" } ?: ""),
    )

/**
 * 보고된 `Content-Type`이 설정된 허용 목록(기본 `video/mp4`)에 없음.
 * HTTP 415 Unsupported Media Type으로 매핑.
 *
 *  - [actual]   클라이언트가 보낸 MIME 타입(없으면 `null`).
 *  - [allowed]  서버가 현재 받아들이려는 MIME 타입 집합 — 호출자가 자가
 *               수정할 수 있도록 응답에 노출된다.
 */
class InvalidVideoMimeTypeException(
    val actual: String?,
    val allowed: Collection<String>,
) : VideoUploadException(
    "Unsupported video MIME type: '${actual ?: "<missing>"}' (allowed: $allowed)",
)

/**
 * 업로드가 설정된 서비스 레벨 크기 한도를 초과. HTTP 413 Payload Too
 * Large로 매핑.
 *
 *  - [actualBytes]  multipart 업로드가 보고한 크기.
 *  - [maxBytes]     설정된 한도
 *                   (`adsignage.max-upload-size-bytes`, 기본 500 MiB).
 */
class VideoTooLargeException(
    val actualBytes: Long,
    val maxBytes: Long,
) : VideoUploadException(
    "Video size $actualBytes bytes exceeds the maximum allowed $maxBytes bytes",
)
