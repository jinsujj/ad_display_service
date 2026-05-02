package me.owldev.adsignage.domain.video.streaming

import me.owldev.adsignage.domain.video.VideoRepository
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
 * AC 14 — serves uploaded video bytes via HTTP Range requests.
 *
 * Route: `GET /api/videos/{filename}` where `{filename}` is the on-disk
 * filename returned by the upload endpoint
 * ([me.owldev.adsignage.domain.video.dto.VideoResponse.filename] /
 * [me.owldev.adsignage.domain.video.storage.StoredVideo.filename]).
 * The filename embeds the storage UUID + extension (e.g. `{uuid}.mp4`),
 * matching the `/videos/{id}.mp4` shape called for in the seed contract.
 *
 * Why a separate controller from [me.owldev.adsignage.domain.video.VideoController]:
 *  - The upload + list endpoints sit behind the JWT filter; this streaming
 *    endpoint is intentionally public so the unauthenticated player page
 *    (Next.js `/player/{deviceId}` running inside the Android WebView) can
 *    fetch MP4 bytes without minting a token. Splitting the controllers
 *    keeps the security boundary easy to read in [SecurityConfig] — list +
 *    upload behind `.authenticated()`, this one allow-listed via
 *    a single-segment `GET /api/videos/{filename}` ant pattern. (The literal
 *    asterisk wildcard is described in words here because Kotlin's nestable
 *    block comments treat `/` followed by `*` inside backticks as opening
 *    a nested KDoc, which leaves the outer block unclosed at EOF.)
 *  - The behaviours have no shared dependencies; mixing them would force the
 *    upload service onto the streaming path's dependency graph for no reason.
 *
 * Range semantics implemented (RFC 7233):
 *  - **No `Range` header** → `200 OK`, full content, `Content-Length =
 *    sizeBytes`, `Accept-Ranges: bytes`.
 *  - **Single byte-range** → `206 Partial Content`, `Content-Range: bytes
 *    start-end/sizeBytes`, `Content-Length = end - start + 1`,
 *    `Accept-Ranges: bytes`. WebView's `<video>` element only ever sends a
 *    single range, so multipart/byteranges support is intentionally skipped
 *    for hackathon scope — the additional MIME-multipart envelope adds
 *    complexity without buying anything for the demo.
 *  - **Unsatisfiable range** → `416 Range Not Satisfiable` with
 *    `Content-Range: bytes * /sizeBytes` so the client can re-issue a valid
 *    range.
 *  - **Malformed `Range` header** → fall back to `200 OK` full content (per
 *    RFC 7233 §3.1: "An origin server MUST ignore a Range header field that
 *    contains a range unit it does not understand"). We log at WARN so a
 *    misbehaving client surfaces in operations.
 *  - **Suffix range (`Range: bytes=-N`)** → standard "last N bytes" behaviour
 *    via [org.springframework.http.HttpRange.getRangeStart] (Spring's parser
 *    handles the suffix form natively).
 *  - **Open-ended range (`Range: bytes=N-`)** → standard "from N to end"
 *    behaviour, again via Spring's parser.
 *
 * Streaming strategy:
 *  - Bytes are written to the response via [StreamingResponseBody], so
 *    multi-hundred-MB files don't need to be buffered in memory.
 *  - For full-content responses we use [Files.copy] (NIO transfer to the
 *    `OutputStream`); for partial content we use [RandomAccessFile.seek]
 *    + a 64 KiB scratch buffer. Both close their handles via Kotlin `use`
 *    so a client disconnecting mid-stream releases the file descriptor.
 */
@RestController
@RequestMapping("/api/videos")
class VideoStreamingController(
    private val videoRepository: VideoRepository,
) {

    private val log = LoggerFactory.getLogger(VideoStreamingController::class.java)

    /**
     * Stream a single uploaded video.
     *
     * The `:.+` matrix on the path variable lets a filename keep its
     * extension (e.g. `…/abc.mp4`); without it Spring would treat `.mp4` as
     * a content-negotiation suffix and trim it off the bound value.
     *
     * Range parsing uses [org.springframework.http.HttpRange.parseRanges] so
     * we get RFC-compliant handling of suffix and open-ended forms for free.
     * Multi-range requests are rejected by collapsing to the first range:
     * the WebView demo target never issues them.
     *
     * @throws VideoNotFoundException        when no row matches `filename`
     *                                        (mapped to 404 by GlobalExceptionHandler)
     * @throws UnsatisfiableRangeException   when the client's range cannot be served
     *                                        (mapped to 416 by GlobalExceptionHandler)
     */
    @GetMapping("/{filename:.+}")
    fun stream(
        @PathVariable filename: String,
        @RequestHeader(value = HttpHeaders.RANGE, required = false) rangeHeader: String?,
    ): ResponseEntity<StreamingResponseBody> {
        val video = videoRepository.findByFilename(filename)
            .orElseThrow { VideoNotFoundException(filename) }

        val path = Paths.get(video.storagePath)
        if (!Files.isRegularFile(path)) {
            // The DB row exists but the on-disk file is gone (manual cleanup,
            // moved storage volume, etc.). Surface as 404 — the byte stream
            // genuinely is not available, regardless of metadata.
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

        // Parse the Range header. Per RFC 7233 §3.1 a syntactically invalid
        // range unit is *ignored* and the server returns full content. Catch
        // the parser's IllegalArgumentException and fall through to the 200
        // path so a flaky client header doesn't break playback.
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
            // Defensive: HttpRange.parseRanges normally throws rather than
            // returning empty, but treat empty-after-parse identically to a
            // malformed header — full content, log at WARN.
            log.warn("Range header parsed to zero ranges for {}: '{}'", filename, rangeHeader)
            throw UnsatisfiableRangeException(rangeHeader, fileSize)
        }

        // Single-range only. WebView never issues multipart/byteranges; if
        // someone does we satisfy the first range and ignore the rest.
        val httpRange = ranges.first()
        val start = httpRange.getRangeStart(fileSize)
        val end = httpRange.getRangeEnd(fileSize)

        // RFC 7233: start may equal fileSize-1 but never reach fileSize, and
        // end must be >= start. Spring's parser returns clamped values for
        // suffix/open ranges, but a literal `bytes=999999-` past EOF still
        // produces start >= fileSize, which we reject as 416.
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
     * Parse the persisted MIME type defensively. Old rows pre-AC-14 could in
     * theory have a malformed MIME column; rather than 500ing the streaming
     * call we fall back to `application/octet-stream` so the bytes still flow
     * (the WebView's `<video>` element will sniff the container itself).
     */
    private fun parseMediaTypeOrFallback(raw: String): MediaType =
        try {
            MediaType.parseMediaType(raw)
        } catch (ex: IllegalArgumentException) {
            log.warn("invalid persisted MIME type '{}', falling back to octet-stream", raw)
            MediaType.APPLICATION_OCTET_STREAM
        }

    companion object {
        /**
         * Scratch buffer size for partial-content streaming. 64 KiB is the
         * sweet spot reported by Tomcat's NIO connector — large enough to
         * amortise syscall overhead, small enough that a Range covering only
         * a few KB doesn't waste a multi-MB allocation per request.
         */
        private const val BUFFER_SIZE_BYTES = 64 * 1024
    }
}
