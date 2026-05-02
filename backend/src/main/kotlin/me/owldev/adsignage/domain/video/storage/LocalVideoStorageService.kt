package me.owldev.adsignage.domain.video.storage

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.UUID

/**
 * [VideoStorageService]의 로컬 파일시스템 구현.
 *
 * [VideoStorageProperties.videoStoragePath](기본 `/var/lib/adsignage/videos`,
 * 환경별 설정 가능)에서 스토리지 루트를 읽고 [Files.copy]로 업로드를 디스크에
 * 직접 스트리밍하므로 수백 MB MP4도 메모리에 버퍼링되지 않는다.
 *
 * 파일명 전략: `{UUID}.{ext}` — `ext`는 광고주의 원본 파일명에서 추출하여
 * 소문자화하고 [ALLOWED_EXTENSIONS]로 검증한다. UUID 접두사가 클라이언트를
 * 신뢰하지 않고도 디스크 상의 유일성을 보장한다.
 *
 * 경로 트래버설 보강:
 *  - `originalName`은 다른 처리 전에 leaf 세그먼트로 축소된다(`../../etc/foo.mp4`
 *    같은 입력은 `foo.mp4`가 됨).
 *  - 계산된 대상 경로는 정규화 후 재검사되어 여전히 설정된 [rootDir] 아래에
 *    있는지 확인 — 병적인 UUID 확장(현실에서는 도달 불가능하지만 싼 보험)을
 *    거절한다.
 *
 * 이 서비스는 [me.owldev.adsignage.domain.video.Video] 행을 영속화 *하지
 * 않는다* — 그것은 호출자(보통 후속 Sub-AC의 `VideoUploadService`)의
 * 책임이다. 영속화와 스토리지를 분리하면 H2/Postgres 엔터티 레이어가 디스크를
 * 건드리지 않고 테스트 가능하고, 외부 영상 파일을 임포트해야 할 때 이
 * 서비스를 재사용할 수 있다.
 */
@Service
class LocalVideoStorageService(
    private val properties: VideoStorageProperties,
) : VideoStorageService {

    private val log = LoggerFactory.getLogger(LocalVideoStorageService::class.java)

    /**
     * 절대 경로로 정규화된 스토리지 루트. 프로퍼티가 애플리케이션 수명 동안
     * 불변이므로 생성 시점에 한 번 계산한다. 테스트는 `@TempDir`을 주입하고,
     * 운영은 `/var/lib/adsignage/videos`를 가리킨다.
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
        /**
         * 허용되는 영상 확장자 화이트리스트. MP4가 데모 타깃이며 나머지는
         * 설정 플래그 없이 테스트 중에 받아들이고 싶을 만한 일반적인
         * WebView 친화 컨테이너다.
         */
        private val ALLOWED_EXTENSIONS = setOf("mp4", "webm", "mov", "m4v")

        /**
         * multipart 업로드가 Content-Type을 생략했을 때 사용하는 폴백 값.
         * 이 서비스의 지배적 케이스가 MP4이므로 안전한 기본값.
         */
        private const val DEFAULT_MIME_TYPE = "video/mp4"

        /**
         * 스트리밍 엔드포인트가 살게 될 정식 URL 접두사. 컨트롤러를 거치지
         * 않고 플레이리스트 페이로드를 조립할 수 있도록 여기서 예측한다.
         * 라우트가 이동하면 양쪽을 업데이트해야 한다.
         */
        private const val URL_PREFIX = "/api/videos"
    }
}
