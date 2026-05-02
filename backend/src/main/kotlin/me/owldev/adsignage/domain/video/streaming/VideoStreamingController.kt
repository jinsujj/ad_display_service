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
 * AC 14 — 업로드된 영상 바이트를 HTTP Range 요청으로 서빙한다.
 *
 * 라우트: `GET /api/videos/{filename}` (`{filename}`은 업로드 엔드포인트가
 * 반환한 디스크 상의 파일명 —
 * [me.owldev.adsignage.domain.video.dto.VideoResponse.filename] /
 * [me.owldev.adsignage.domain.video.storage.StoredVideo.filename]).
 * 파일명은 저장 UUID + 확장자 형태(예: `{uuid}.mp4`)이며, Seed 계약의
 * `/videos/{id}.mp4` 형태와 일치한다.
 *
 * [me.owldev.adsignage.domain.video.VideoController]와 컨트롤러를 분리한 이유:
 *  - 업로드 + 목록 엔드포인트는 JWT 필터 뒤에 있는 반면, 이 스트리밍
 *    엔드포인트는 의도적으로 공개되어 있어 인증되지 않은 플레이어 페이지
 *    (안드로이드 WebView 안의 Next.js `/player/{deviceId}`)가 토큰 없이
 *    MP4 바이트를 받을 수 있다. 컨트롤러를 분리하면 [SecurityConfig]에서
 *    보안 경계가 명확해진다 — 목록/업로드는 `.authenticated()`,
 *    이 엔드포인트는 단일 세그먼트 `GET /api/videos/{filename}` ant
 *    패턴으로 화이트리스트에 추가. (Kotlin의 중첩 블록 주석 제약상 별표
 *    와일드카드를 텍스트로 풀어 설명한다 — 백틱 안의 `/` 다음 `*`는
 *    중첩 KDoc 시작으로 해석되어 외부 블록이 EOF까지 닫히지 않는다.)
 *  - 두 동작은 의존성을 공유하지 않으므로 합치면 업로드 서비스가 스트리밍
 *    경로의 의존성 그래프에 불필요하게 끌려 들어간다.
 *
 * 구현된 Range 시맨틱 (RFC 7233):
 *  - **`Range` 헤더 없음** → `200 OK`, 전체 콘텐츠, `Content-Length =
 *    sizeBytes`, `Accept-Ranges: bytes`.
 *  - **단일 바이트 범위** → `206 Partial Content`, `Content-Range: bytes
 *    start-end/sizeBytes`, `Content-Length = end - start + 1`,
 *    `Accept-Ranges: bytes`. WebView의 `<video>` 요소는 항상 단일 범위만
 *    보내므로 multipart/byteranges 지원은 해커톤 범위에서 의도적으로 생략 —
 *    추가 MIME-multipart 봉투는 데모에 아무 이득 없이 복잡도만 늘린다.
 *  - **충족 불가능한 범위** → `416 Range Not Satisfiable` 응답에
 *    `Content-Range: bytes * /sizeBytes`를 실어 클라이언트가 유효한 범위로
 *    재요청할 수 있게 한다.
 *  - **잘못된 형식의 `Range` 헤더** → `200 OK` 전체 콘텐츠로 폴백
 *    (RFC 7233 §3.1: "원 서버는 자신이 이해하지 못하는 범위 단위를 포함한
 *    Range 헤더 필드를 무시해야 한다"). 잘못 동작하는 클라이언트가 운영 단에
 *    드러나도록 WARN 레벨로 로그한다.
 *  - **접미 범위 (`Range: bytes=-N`)** → 표준 "마지막 N 바이트" 동작
 *    ([org.springframework.http.HttpRange.getRangeStart]를 통해 — Spring 파서가
 *    접미 형태를 네이티브로 처리).
 *  - **개방형 범위 (`Range: bytes=N-`)** → 표준 "N부터 끝까지" 동작, 역시
 *    Spring 파서를 통해.
 *
 * 스트리밍 전략:
 *  - 바이트는 [StreamingResponseBody]로 응답에 기록되므로 수백 MB 파일을
 *    메모리에 버퍼링할 필요가 없다.
 *  - 전체 콘텐츠 응답은 [Files.copy] (NIO transfer to `OutputStream`),
 *    부분 콘텐츠는 [RandomAccessFile.seek] + 64 KiB 스크래치 버퍼 사용.
 *    둘 다 Kotlin `use`로 핸들을 닫으므로 클라이언트가 스트림 도중
 *    연결을 끊으면 파일 디스크립터가 해제된다.
 */
@RestController
@RequestMapping("/api/videos")
class VideoStreamingController(
    private val videoRepository: VideoRepository,
) {

    private val log = LoggerFactory.getLogger(VideoStreamingController::class.java)

    /**
     * 업로드된 영상 한 건을 스트리밍한다.
     *
     * path variable의 `:.+` 매처는 파일명이 확장자(예: `…/abc.mp4`)를
     * 유지하도록 한다 — 없으면 Spring이 `.mp4`를 콘텐츠 협상 접미사로
     * 간주해 바인딩 값에서 잘라낸다.
     *
     * Range 파싱은 [org.springframework.http.HttpRange.parseRanges]를 사용해
     * 접미/개방 형태에 대한 RFC 호환 처리를 무료로 얻는다. 다중 범위 요청은
     * 첫 범위로 축약 — WebView 데모 대상은 다중 범위를 보낸 적이 없다.
     *
     * @throws VideoNotFoundException        `filename`에 해당하는 행이 없을 때
     *                                        (GlobalExceptionHandler가 404로 매핑)
     * @throws UnsatisfiableRangeException   클라이언트의 범위가 서비스 불가능할 때
     *                                        (GlobalExceptionHandler가 416으로 매핑)
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
            // DB 행은 있지만 디스크의 파일이 사라진 경우(수동 정리, 스토리지
            // 볼륨 이동 등). 메타데이터와 무관하게 바이트 스트림이 실제로
            // 존재하지 않으므로 404로 노출한다.
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

        // Range 헤더 파싱. RFC 7233 §3.1에 따라 문법적으로 유효하지 않은
        // 범위 단위는 *무시*하고 서버는 전체 콘텐츠를 반환한다. 파서의
        // IllegalArgumentException을 잡고 200 경로로 폴백시켜 헤더가
        // 깨진 클라이언트가 재생을 끊지 못하게 한다.
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
            // 방어적 처리: HttpRange.parseRanges는 보통 빈 결과 대신 예외를
            // 던지지만, 파싱 후 비어있는 경우도 잘못된 헤더와 동일하게 다룬다 —
            // 전체 콘텐츠 + WARN 로그.
            log.warn("Range header parsed to zero ranges for {}: '{}'", filename, rangeHeader)
            throw UnsatisfiableRangeException(rangeHeader, fileSize)
        }

        // 단일 범위만 처리. WebView는 multipart/byteranges를 보내지 않으며,
        // 누군가 보내더라도 첫 범위만 채우고 나머지는 무시한다.
        val httpRange = ranges.first()
        val start = httpRange.getRangeStart(fileSize)
        val end = httpRange.getRangeEnd(fileSize)

        // RFC 7233: start는 fileSize-1까지 가능하고 fileSize에 도달해서는
        // 안 되며, end는 start 이상이어야 한다. Spring 파서는 접미/개방
        // 범위에 대해 클램핑된 값을 반환하지만, EOF를 넘은 명시적
        // `bytes=999999-` 같은 요청은 여전히 start >= fileSize를 만들고
        // 이 경우 416으로 거절한다.
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
     * 영속화된 MIME 타입을 방어적으로 파싱한다. AC-14 이전의 오래된 행은
     * 이론상 손상된 MIME 컬럼을 가질 수 있는데, 그때 스트리밍 호출을 500으로
     * 망가뜨리지 않고 `application/octet-stream`으로 폴백해 바이트가 계속
     * 흐르게 한다(WebView의 `<video>` 요소가 컨테이너를 자체 스니핑한다).
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
         * 부분 콘텐츠 스트리밍을 위한 스크래치 버퍼 크기. Tomcat NIO 커넥터가
         * 보고하는 최적값이 64 KiB — syscall 오버헤드를 충분히 분산하면서도,
         * 단 몇 KB만 커버하는 Range가 요청당 수 MB 할당을 낭비하지 않을 만큼
         * 작다.
         */
        private const val BUFFER_SIZE_BYTES = 64 * 1024
    }
}
