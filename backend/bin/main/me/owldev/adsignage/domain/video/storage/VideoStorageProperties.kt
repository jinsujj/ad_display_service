package me.owldev.adsignage.domain.video.storage

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 디스크 상의 영상 스토리지 루트와 업로드 검증 임계값을 위한 타입 안전
 * 바인딩.
 *
 * `application.yml`의 `adsignage.*`에 매핑(환경변수로 오버라이드 가능).
 * 스프링의 relaxed 바인딩이 kebab-case 키를 여기의 camelCase 카운터파트로
 * 매핑한다.
 *
 *  - [videoStoragePath] — 업로드된 MP4 바이트가 사는 절대 파일시스템
 *    경로. 운영 배포 레이아웃과 일치하도록 `/var/lib/adsignage/videos`가
 *    기본값. 테스트는 임시 디렉토리를 주입한다.
 *
 *  - [maxUploadSizeBytes] — Sub-AC 3 서비스 레벨 크기 캡으로, 디스크에
 *    *닿기 전에* 평가된다. 스프링의 `spring.servlet.multipart.max-file-size`와
 *    독립적: 후자는 파서 단계에서 요청을 튕기고 스프링 자체의
 *    `MaxUploadSizeExceededException`을 반환하지만, 이 값은 타입화된
 *    [me.owldev.adsignage.domain.video.upload.VideoTooLargeException]을 산출해
 *    API 계약이 도메인 용어로 말할 수 있게 하고(추후 multipart 파서가
 *    재설정되어도 동일한 동작을 유지). `application.yml`의 multipart 한계와
 *    맞추기 위해 500 MiB가 기본값.
 *
 *  - [allowedMimeTypes] — Sub-AC 3 엄격한 MIME 화이트리스트. Seed 계약에
 *    따라 데모 타깃은 `video/mp4`이며, 테스트 픽스처나 `video/quicktime`으로
 *    인코딩된 데모 영상이 코드 변경 없이 설정으로 옵트인할 수 있도록
 *    단일 값이 아닌 리스트로 둔다. 기본값은 단일 정식 타입.
 */
@ConfigurationProperties(prefix = "adsignage")
data class VideoStorageProperties(
    val videoStoragePath: String = "/var/lib/adsignage/videos",
    val maxUploadSizeBytes: Long = DEFAULT_MAX_UPLOAD_SIZE_BYTES,
    val allowedMimeTypes: List<String> = listOf("video/mp4"),
) {
    companion object {
        /** 500 MiB — `spring.servlet.multipart.max-file-size`와 일치. */
        const val DEFAULT_MAX_UPLOAD_SIZE_BYTES: Long = 500L * 1024 * 1024
    }
}
