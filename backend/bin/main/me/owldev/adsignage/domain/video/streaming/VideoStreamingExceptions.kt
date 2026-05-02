package me.owldev.adsignage.domain.video.streaming

/**
 * Typed exceptions raised by the video streaming endpoint
 * (`GET /api/videos/{filename}`).
 *
 * AC 14 — HTTP Range request streaming. The streaming endpoint must return
 * semantically meaningful HTTP statuses (404 for an unknown filename, 416 for
 * a Range request the server cannot satisfy) without being mis-handled by the
 * upload-flow exception mappers. Defining the failures as domain types keeps
 * them out of [me.owldev.adsignage.domain.video.upload.VideoUploadException]'s
 * sealed hierarchy — the upload pipeline maps to 400/413/415, the streaming
 * pipeline maps to 404/416, so they share no inheritance relationship.
 *
 * Both subclasses are mapped to HTTP statuses by
 * [me.owldev.adsignage.web.GlobalExceptionHandler].
 */
sealed class VideoStreamingException(message: String) : RuntimeException(message)

/**
 * The requested filename does not match any persisted [me.owldev.adsignage.domain.video.Video]
 * row, or the on-disk bytes have been removed since the row was inserted.
 *
 * Mapped to HTTP 404 Not Found.
 */
class VideoNotFoundException(val filename: String) :
    VideoStreamingException("No video found for filename '$filename'")

/**
 * The client sent a syntactically valid `Range` header but the requested byte
 * window cannot be satisfied (e.g. start offset >= file size).
 *
 * Mapped to HTTP 416 Range Not Satisfiable. Per RFC 7233 §4.4 the response
 * MUST carry a `Content-Range: bytes * /completeLength` header so the client
 * can re-issue a valid range; the controller sets that header before raising
 * this exception's response.
 */
class UnsatisfiableRangeException(
    val rangeHeader: String,
    val fileSize: Long,
) : VideoStreamingException(
    "Range '$rangeHeader' cannot be satisfied for file of size $fileSize bytes",
)
