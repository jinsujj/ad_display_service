package me.owldev.adsignage.bounded.context.video.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 디스크 상의 영상 스토리지 루트와 업로드 검증 임계값을 위한 타입 안전
 * 바인딩.
 *
 * `application.yml`의 `adsignage.*`에 매핑(환경변수로 오버라이드 가능).
 *
 *  - [videoStoragePath] — 업로드된 MP4 바이트가 사는 절대 파일시스템 경로.
 *  - [maxUploadSizeBytes] — 서비스 레벨 크기 캡(디스크 닿기 전 평가).
 *  - [allowedMimeTypes] — 엄격한 MIME 화이트리스트.
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
