package me.owldev.adsignage.bounded.context.video.adapter.`in`.api

import me.owldev.adsignage.bounded.context.video.application.port.out.database.VideoRepositoryPort
import me.owldev.adsignage.bounded.context.video.domain.exception.UnsatisfiableRangeException
import me.owldev.adsignage.bounded.context.video.domain.exception.VideoNotFoundException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Paths

/**
 * AC 14 — 업로드된 영상 바이트를 HTTP Range 요청으로 서빙한다.
 *
 * 라우트: `GET /api/videos/{filename}` — 의도적으로 공개(JWT 없음)이며
 * SecurityConfig 에서 화이트리스트.
 *
 * 구현된 Range 시맨틱 (RFC 7233):
 *  - **`Range` 헤더 없음** → `200 OK`, 전체 콘텐츠.
 *  - **단일 바이트 범위** → `206 Partial Content`, `Content-Range: bytes
 *    start-end/sizeBytes`.
 *  - **충족 불가능한 범위** → `416 Range Not Satisfiable`.
 *  - **잘못된 형식의 `Range` 헤더** → `200 OK` 전체 콘텐츠로 폴백.
 *  - **접미/개방 범위** → Spring 파서를 통해 표준 처리.
 *
 * 스트리밍 전략:
 *  - [StreamingResponseBody] — 수백 MB 파일을 메모리에 버퍼링하지 않음.
 *  - 전체 콘텐츠는 [Files.copy] (NIO transfer), 부분 콘텐츠는
 *    [RandomAccessFile.seek] + 64 KiB 스크래치 버퍼.
 */
@RestController
@RequestMapping("/api/videos")
class VideoStreamingController(
    private val videoRepositoryPort: VideoRepositoryPort,
) {

    private val log = LoggerFactory.getLogger(VideoStreamingController::class.java)

    @GetMapping("/{filename:.+}")
    fun stream(
        @PathVariable filename: String,
        @RequestHeader(value = HttpHeaders.RANGE, required = false) rangeHeader: String?,
    ): ResponseEntity<StreamingResponseBody> {
        val video = videoRepositoryPort.findByFilename(filename)
            ?: throw VideoNotFoundException(filename)

        val path = Paths.get(video.storagePath)
        if (!Files.isRegularFile(path)) {
            log.warn(
                "video metadata exists but file is missing on disk: filename={} path={}",
                filename, video.storagePath,
            )
            throw VideoNotFoundException(filename)
        }

        val fileSize = video.sizeBytes
        val mediaType = parseMediaTypeOrFallback(video.mimeType)

        if (rangeHeader.isNullOrBlank()) {
            log.debug(
                "GET /api/videos/{} full-content response size={}B mime={}",
                filename, fileSize, mediaType,
            )
            val body = StreamingResponseBody { out ->
                Files.newInputStream(path).use { input -> input.copyTo(out) }
            }
            return ResponseEntity.ok()
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .header(HttpHeaders.CONTENT_LENGTH, fileSize.toString())
                .contentType(mediaType)
                .body(body)
        }

        // RFC 7233 §3.1: 문법적으로 유효하지 않은 범위 단위는 *무시*하고
        // 전체 콘텐츠 반환. 파서의 IllegalArgumentException 을 잡고 200 경로로 폴백.
        val ranges = try {
            org.springframework.http.HttpRange.parseRanges(rangeHeader)
        } catch (ex: IllegalArgumentException) {
            log.warn(
                "ignoring malformed Range header for {}: '{}' ({})",
                filename, rangeHeader, ex.message,
            )
            val body = StreamingResponseBody { out ->
                Files.newInputStream(path).use { input -> input.copyTo(out) }
            }
            return ResponseEntity.ok()
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .header(HttpHeaders.CONTENT_LENGTH, fileSize.toString())
                .contentType(mediaType)
                .body(body)
        }

        if (ranges.isEmpty()) {
            log.warn("Range header parsed to zero ranges for {}: '{}'", filename, rangeHeader)
            throw UnsatisfiableRangeException(rangeHeader, fileSize)
        }

        // 단일 범위만 처리. WebView 는 multipart/byteranges 를 보내지 않음.
        val httpRange = ranges.first()
        val start = httpRange.getRangeStart(fileSize)
        val end = httpRange.getRangeEnd(fileSize)

        if (start >= fileSize || end < start) {
            log.warn(
                "Range '{}' is unsatisfiable for {} (size={}B): start={} end={}",
                rangeHeader, filename, fileSize, start, end,
            )
            throw UnsatisfiableRangeException(rangeHeader, fileSize)
        }

        val length = end - start + 1
        log.debug(
            "GET /api/videos/{} partial-content range={}-{}/{} length={}B mime={}",
            filename, start, end, fileSize, length, mediaType,
        )

        val body = StreamingResponseBody { out ->
            RandomAccessFile(path.toFile(), "r").use { raf ->
                raf.seek(start)
                val buf = ByteArray(BUFFER_SIZE_BYTES)
                var remaining = length
                while (remaining > 0) {
                    val toRead = minOf(buf.size.toLong(), remaining).toInt()
                    val read = raf.read(buf, 0, toRead)
                    if (read == -1) break
                    out.write(buf, 0, read)
                    remaining -= read.toLong()
                }
            }
        }

        return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
            .header(HttpHeaders.ACCEPT_RANGES, "bytes")
            .header(HttpHeaders.CONTENT_RANGE, "bytes $start-$end/$fileSize")
            .header(HttpHeaders.CONTENT_LENGTH, length.toString())
            .contentType(mediaType)
            .body(body)
    }

    /**
     * 영속화된 MIME 타입을 방어적으로 파싱한다. 손상된 MIME 컬럼이 스트리밍
     * 호출을 500 으로 망가뜨리지 않도록 `application/octet-stream`으로 폴백.
     */
    private fun parseMediaTypeOrFallback(raw: String): MediaType =
        try {
            MediaType.parseMediaType(raw)
        } catch (ex: IllegalArgumentException) {
            log.warn("invalid persisted MIME type '{}', falling back to octet-stream", raw)
            MediaType.APPLICATION_OCTET_STREAM
        }

    companion object {
        /** 부분 콘텐츠 스트리밍 스크래치 버퍼 — 64 KiB. */
        private const val BUFFER_SIZE_BYTES = 64 * 1024
    }
}
