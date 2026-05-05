package me.owldev.adsignage.bounded.context.video.application.service

import me.owldev.adsignage.bounded.context.video.application.port.out.database.VideoRepositoryPort
import me.owldev.adsignage.bounded.context.video.application.port.out.storage.VideoStoragePort
import me.owldev.adsignage.bounded.context.video.config.VideoStorageProperties
import me.owldev.adsignage.bounded.context.video.domain.dto.StoredVideo
import me.owldev.adsignage.bounded.context.video.domain.exception.EmptyVideoUploadException
import me.owldev.adsignage.bounded.context.video.domain.exception.InvalidVideoMimeTypeException
import me.owldev.adsignage.bounded.context.video.domain.exception.MissingVideoFilenameException
import me.owldev.adsignage.bounded.context.video.domain.exception.VideoTooLargeException
import me.owldev.adsignage.bounded.context.video.domain.model.Video
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.multipart.MultipartFile
import java.util.Locale

/**
 * 광고주가 업로드한 영상을 받기 위한 도메인 진입점.
 *
 *  - **MIME 검증** — `allowedMimeTypes`(기본 `["video/mp4"]`)에 포함된 타입만
 *    허용 — 그 외는 [InvalidVideoMimeTypeException].
 *  - **크기 강제** — `maxUploadSizeBytes`(기본 500 MiB) 초과 시
 *    [VideoTooLargeException].
 *  - **타입화된 예외** — 모든 실패 모드는 sealed VideoUploadException 의
 *    하위 클래스를 발생시키고, GlobalExceptionHandler 가 400/413/415 로 매핑.
 *
 * 검증 통과 시 실제 파일시스템 쓰기는 [VideoStoragePort]에 위임하고,
 * [VideoRepositoryPort]에 [Video] 행을 영속화한다.
 */
@Service
class VideoUploadService(
    private val videoStoragePort: VideoStoragePort,
    private val videoRepositoryPort: VideoRepositoryPort,
    private val properties: VideoStorageProperties,
) {

    private val log = LoggerFactory.getLogger(VideoUploadService::class.java)

    /**
     * 설정된 MIME 화이트리스트의 소문자 사본 — 일부 브라우저가 `Video/MP4`
     * 같은 형태로 리포트하기에 비교를 대소문자 무시로 한다.
     */
    private val allowedMimeTypes: Set<String> =
        properties.allowedMimeTypes.map { it.lowercase(Locale.ROOT) }.toSet()

    @Transactional
    fun upload(file: MultipartFile, advertiserId: String): Video {
        require(advertiserId.isNotBlank()) { "advertiserId must not be blank" }

        validate(file)

        val stored: StoredVideo = videoStoragePort.store(file)

        val video = Video(
            advertiserId = advertiserId,
            filename = stored.filename,
            originalName = stored.originalName,
            mimeType = stored.mimeType,
            sizeBytes = stored.sizeBytes,
            storagePath = stored.storagePath,
        )
        val saved = videoRepositoryPort.save(video)

        log.info(
            "video uploaded id={} filename={} advertiserId={} size={}B mime={}",
            saved.id, saved.filename, saved.advertiserId, saved.sizeBytes, saved.mimeType,
        )
        return saved
    }

    /**
     * 4가지 잘못된 입력 모드에 대해 업로드를 사전에 거절. 순서가 중요:
     * 비어있음 → 파일명 → MIME → 크기.
     */
    private fun validate(file: MultipartFile) {
        if (file.isEmpty) {
            throw EmptyVideoUploadException()
        }

        val rawOriginal = file.originalFilename
        if (rawOriginal.isNullOrBlank()) {
            throw MissingVideoFilenameException(rawOriginal)
        }

        val mimeType = file.contentType?.trim()?.lowercase(Locale.ROOT)
        if (mimeType.isNullOrBlank() || mimeType !in allowedMimeTypes) {
            throw InvalidVideoMimeTypeException(
                actual = file.contentType,
                allowed = properties.allowedMimeTypes,
            )
        }

        val size = file.size
        if (size > properties.maxUploadSizeBytes) {
            throw VideoTooLargeException(
                actualBytes = size,
                maxBytes = properties.maxUploadSizeBytes,
            )
        }
    }
}
