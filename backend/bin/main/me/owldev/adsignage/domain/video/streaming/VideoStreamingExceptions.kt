package me.owldev.adsignage.domain.video.streaming

/**
 * 영상 스트리밍 엔드포인트(`GET /api/videos/{filename}`)가 발생시키는
 * 타입화된 예외.
 *
 * AC 14 — HTTP Range 요청 스트리밍. 스트리밍 엔드포인트는 업로드 흐름
 * 예외 매퍼에 잘못 처리되지 않으면서 의미상 명확한 HTTP 상태(알 수 없는
 * 파일명에 대해 404, 서버가 충족할 수 없는 Range 요청에 대해 416)를
 * 반환해야 한다. 실패를 도메인 타입으로 정의하면
 * [me.owldev.adsignage.domain.video.upload.VideoUploadException]의 sealed
 * 계층 밖에 두게 된다 — 업로드 파이프라인은 400/413/415, 스트리밍
 * 파이프라인은 404/416으로 매핑되므로 상속 관계를 공유하지 않는다.
 *
 * 두 하위 클래스 모두 [me.owldev.adsignage.web.GlobalExceptionHandler]가
 * HTTP 상태로 매핑한다.
 */
sealed class VideoStreamingException(message: String) : RuntimeException(message)

/**
 * 요청된 파일명이 어떤 영속화된 [me.owldev.adsignage.domain.video.Video]
 * 행과도 일치하지 않거나, 행이 삽입된 이후 디스크 상의 바이트가 제거된 경우.
 *
 * HTTP 404 Not Found로 매핑.
 */
class VideoNotFoundException(val filename: String) :
    VideoStreamingException("No video found for filename '$filename'")

/**
 * 클라이언트가 구문상 유효한 `Range` 헤더를 보냈으나 요청된 바이트 윈도우를
 * 충족할 수 없는 경우(예: start offset >= 파일 크기).
 *
 * HTTP 416 Range Not Satisfiable로 매핑. RFC 7233 §4.4에 따라 응답은
 * 클라이언트가 유효한 range를 재발행할 수 있도록 `Content-Range:
 * bytes * /completeLength` 헤더를 반드시 포함해야 하며 — 컨트롤러가
 * 이 예외의 응답을 발생시키기 전에 해당 헤더를 설정한다.
 */
class UnsatisfiableRangeException(
    val rangeHeader: String,
    val fileSize: Long,
) : VideoStreamingException(
    "Range '$rangeHeader' cannot be satisfied for file of size $fileSize bytes",
)
