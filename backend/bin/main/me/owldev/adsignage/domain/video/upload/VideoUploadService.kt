package me.owldev.adsignage.domain.video.upload

import me.owldev.adsignage.domain.video.Video
import me.owldev.adsignage.domain.video.VideoRepository
import me.owldev.adsignage.domain.video.storage.StoredVideo
import me.owldev.adsignage.domain.video.storage.VideoStorageProperties
import me.owldev.adsignage.domain.video.storage.VideoStorageService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.multipart.MultipartFile
import java.util.Locale

/**
 * 광고주가 업로드한 영상을 받기 위한 도메인 진입점.
 *
 * Sub-AC 3 범위:
 *  - **MIME 검증** — [VideoStorageProperties.allowedMimeTypes]
 *    (기본 `["video/mp4"]`)에 포함된 타입만 허용 — 그 외(누락된 `Content-Type`
 *    포함)는 [InvalidVideoMimeTypeException]을 발생시킨다.
 *  - **크기 강제** — [VideoStorageProperties.maxUploadSizeBytes]
 *    (기본 500 MiB)를 초과하는 업로드는 [VideoTooLargeException]을
 *    발생시킨다. 두 검사 모두 디스크에 단 1바이트도 쓰기 *전에*
 *    multipart 메타데이터로 실행되어 악의적 클라이언트가 서버 파일시스템을
 *    스팸하지 못하게 한다.
 *  - **타입화된 예외** — 모든 실패 모드는 sealed [VideoUploadException]의
 *    하위 클래스를 발생시킨다. [me.owldev.adsignage.web.GlobalExceptionHandler]가
 *    각 하위 클래스를 의미상 올바른 HTTP 상태(400/413/415)에 매핑한다.
 *
 * 검증을 통과하면 실제 파일시스템 쓰기는 [VideoStorageService](로컬 디스크
 * 구현은 `LocalVideoStorageService`)에 위임하고, 스트리밍 엔드포인트가
 * 매 Range 요청마다 파일을 다시 stat하지 않고도 `filename → storage_path
 * + content-type + size_bytes`를 해석할 수 있도록 [Video] 행을 영속화한다.
 *
 * 왜 이 로직을 [VideoStorageService]에 합치지 않는가?
 *  - 스토리지는 *바이트가 어디로 가는가*를 다루고, 업로드는 *바이트가
 *    애초에 허용되는가*를 다룬다. 분리 덕분에 `LocalVideoStorageService`는
 *    HTTP 모양의 MIME/크기 검사가 무관한 비-HTTP 임포트(예: 미래의
 *    seed 데이터 로더)에서도 재사용 가능하다.
 *  - 또한 업로드 테스트가 `@TempDir` 없이 *타입화된* 예외를 단언할 수 있다 —
 *    스토리지 서비스가 모킹되므로 MIME/크기 거절이 어떤 I/O보다 먼저
 *    단락된다.
 */
@Service
class VideoUploadService(
    private val videoStorageService: VideoStorageService,
    private val videoRepository: VideoRepository,
    private val properties: VideoStorageProperties,
) {

    private val log = LoggerFactory.getLogger(VideoUploadService::class.java)

    /**
     * 설정된 MIME 화이트리스트의 소문자 사본 — 일부 브라우저가 `Video/MP4`
     * 같은 형태로 리포트하기에 비교를 대소문자 무시로 한다.
     */
    private val allowedMimeTypes: Set<String> =
        properties.allowedMimeTypes.map { it.lowercase(Locale.ROOT) }.toSet()

    /**
     * 광고주가 제공한 영상 업로드를 검증, 영속화, 기록한다.
     *
     * @param file 들어오는 multipart 업로드.
     * @param advertiserId 컨트롤러의
     *   `@AuthenticationPrincipal AdvertiserPrincipal`에서 가져온 JWT-주장
     *   소유자 id. 컨트롤러가 직접 `Video`를 만드는 대신 서비스 계층에서
     *   소유권을 도장 찍는다는 것은, 미래의 seed 데이터 임포터나 어드민
     *   재업로드를 포함한 모든 업로더 경로가 동일한 소유권 필수 시그니처를
     *   강제로 거치게 한다는 의미다.
     *
     * @return 영속화된 [Video] 엔터티 (서버 할당 id, filename, storage path,
     *   재-stat된 `sizeBytes`, 제공된 [advertiserId] 포함).
     *
     * @throws EmptyVideoUploadException        multipart 본문이 0바이트일 때
     * @throws MissingVideoFilenameException    업로드에 사용할 수 있는 파일명이 없을 때
     * @throws InvalidVideoMimeTypeException    `Content-Type`이 누락되었거나 미지원일 때
     * @throws VideoTooLargeException           업로드가 설정된 캡을 초과할 때
     */
    @Transactional
    fun upload(file: MultipartFile, advertiserId: String): Video {
        require(advertiserId.isNotBlank()) { "advertiserId must not be blank" }

        validate(file)

        val stored: StoredVideo = videoStorageService.store(file)

        val video = Video(
            advertiserId = advertiserId,
            filename = stored.filename,
            originalName = stored.originalName,
            mimeType = stored.mimeType,
            sizeBytes = stored.sizeBytes,
            storagePath = stored.storagePath,
        )
        val saved = videoRepository.save(video)

        log.info(
            "video uploaded id={} filename={} advertiserId={} size={}B mime={}",
            saved.id, saved.filename, saved.advertiserId, saved.sizeBytes, saved.mimeType,
        )
        return saved
    }

    /**
     * 4가지 잘못된 입력 모드에 대해 업로드를 사전에 거절한다.
     *
     * 순서가 중요하다: 비어있음이 가장 싼 검사, 그다음 파일명 존재 여부,
     * 그다음 MIME(바이트를 단 한 번도 읽지 않고 거절), 마지막으로 크기.
     * MIME을 크기보다 먼저 두면 1 GiB `application/zip` 업로드가 "너무 큼"
     * 대신 "잘못된 타입"으로 보고된다 — 클라이언트가 실제로 고쳐야 할
     * 항목에 더 가깝다.
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

        // MultipartFile.size 는 이미 버퍼링된 업로드의 메모리/디스크 상
        // 바이트 수다 — 크기 제한의 권위 있는 값.
        val size = file.size
        if (size > properties.maxUploadSizeBytes) {
            throw VideoTooLargeException(
                actualBytes = size,
                maxBytes = properties.maxUploadSizeBytes,
            )
        }
    }
}
