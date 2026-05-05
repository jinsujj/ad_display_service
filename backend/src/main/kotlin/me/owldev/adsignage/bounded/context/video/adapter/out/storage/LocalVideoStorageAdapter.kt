package me.owldev.adsignage.bounded.context.video.adapter.out.storage

import me.owldev.adsignage.bounded.context.video.application.port.out.storage.VideoStoragePort
import me.owldev.adsignage.bounded.context.video.config.VideoStorageProperties
import me.owldev.adsignage.bounded.context.video.domain.dto.StoredVideo
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.multipart.MultipartFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.UUID

/**
 * [VideoStoragePort]의 로컬 파일시스템 어댑터.
 *
 * [VideoStorageProperties.videoStoragePath](기본 `/var/lib/adsignage/videos`,
 * 환경별 설정 가능)에서 스토리지 루트를 읽고 [Files.copy]로 업로드를 디스크에
 * 직접 스트리밍하므로 수백 MB MP4도 메모리에 버퍼링되지 않는다.
 *
 * 파일명 전략: `{UUID}.{ext}` — 광고주의 원본 파일명에서 확장자 추출, 소문자화,
 * [ALLOWED_EXTENSIONS]로 검증.
 *
 * 경로 트래버설 보강:
 *  - `originalName`은 leaf 세그먼트로 축소된다(`../../etc/foo.mp4` → `foo.mp4`).
 *  - 계산된 대상 경로는 정규화 후 재검사되어 여전히 [rootDir] 아래에 있는지 확인.
 */
@Component
class LocalVideoStorageAdapter(
    private val properties: VideoStorageProperties,
) : VideoStoragePort {

    private val log = LoggerFactory.getLogger(LocalVideoStorageAdapter::class.java)

    /**
     * 절대 경로로 정규화된 스토리지 루트. 프로퍼티가 애플리케이션 수명 동안
     * 불변이므로 생성 시점에 한 번 계산.
     */
    private val rootDir: Path = Paths.get(properties.videoStoragePath)
        .toAbsolutePath()
        .normalize()

    override fun store(file: MultipartFile): StoredVideo {
        require(!file.isEmpty) { "Uploaded video is empty" }

        val rawOriginal = file.originalFilename
            ?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("Uploaded video has no original filename")

        // 확장자를 보기도 전에 `../../etc/foo.mp4` 같은 입력을 무력화하기
        // 위해 leaf 이름으로 축소.
        val safeOriginal = Paths.get(rawOriginal).fileName?.toString()
            ?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("Uploaded video has no usable filename: $rawOriginal")

        val extension = safeOriginal
            .substringAfterLast('.', missingDelimiterValue = "")
            .lowercase(Locale.ROOT)
        require(extension.isNotBlank() && extension in ALLOWED_EXTENSIONS) {
            "Unsupported video extension: '$extension' (allowed: $ALLOWED_EXTENSIONS)"
        }

        // 멱등: rootDir이 이미 존재하면 createDirectories는 no-op.
        Files.createDirectories(rootDir)

        val storedFilename = "${UUID.randomUUID()}.$extension"
        val target = rootDir.resolve(storedFilename).normalize()
        check(target.startsWith(rootDir)) {
            "Resolved storage path escaped the configured root: $target"
        }

        file.inputStream.use { input ->
            Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING)
        }

        // MultipartFile.size를 신뢰하지 않고 파일을 다시 stat — Spring의
        // 값은 엣지 케이스에서 -1일 수 있고, 어차피 디스크 상의 진실이
        // 스트리밍 엔드포인트가 Content-Length로 서빙할 값이다.
        val sizeBytes = Files.size(target)
        val mimeType = file.contentType
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_MIME_TYPE

        log.info(
            "video stored filename={} size={}B mime={} path={}",
            storedFilename, sizeBytes, mimeType, target,
        )

        return StoredVideo(
            filename = storedFilename,
            originalName = safeOriginal,
            mimeType = mimeType,
            sizeBytes = sizeBytes,
            storagePath = target.toString(),
            urlPath = "$URL_PREFIX/$storedFilename",
        )
    }

    companion object {
        private val ALLOWED_EXTENSIONS = setOf("mp4", "webm", "mov", "m4v")
        private const val DEFAULT_MIME_TYPE = "video/mp4"
        private const val URL_PREFIX = "/api/videos"
    }
}
