package me.owldev.adsignage.bounded.context.video.domain.model

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * 업로드된 비디오 자산을 저장.
 *
 * Flyway 마이그레이션 V30이 생성한 `videos` 테이블에 매핑.
 *
 * `Video`는 광고주가 서버에 업로드한 MP4의 *파일 시스템 측* 레코드. 더
 * 상위의 `Ad` 개념과 의도적으로 분리됨: 동일한 비디오가 원칙적으로 여러
 * 광고를 뒷받침할 수 있고(또한 광고의 라이프사이클은 기저 파일의
 * 라이프사이클과 다름 — 소프트 삭제된 광고가 재생 도중 디스크 파일을
 * 고아로 만들면 안 됨).
 *
 * 표현된 온톨로지 개념:
 *  - ad_video_filename → [filename] (디스크 저장, 서버 생성, 유일)
 *  - ad_advertiser_id  → [advertiserId] (FK → advertisers.id, NOT NULL)
 */
@Entity
@Table(name = "videos")
class Video(
    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    val id: String = UUID.randomUUID().toString(),

    /**
     * 소유 광고주의 id (FK → `advertisers.id`).
     *
     * 업로드 시 JWT principal에서 설정. 데이터 격리 계약(AC 4)은 로그인한
     * 광고주에게 [Video]를 표면화하는 모든 읽기 경로가 이 컬럼으로 필터링
     * 하도록 요구하여 관리자 UI가 다른 광고주의 업로드를 절대 보지 못하게 함.
     */
    @Column(name = "advertiser_id", nullable = false, updatable = false, length = 36)
    val advertiserId: String,

    /**
     * 디스크에 사용되는 서버 생성 파일명. 테이블 전체에서 유일.
     */
    @Column(name = "filename", nullable = false, unique = true, length = 255)
    val filename: String,

    /**
     * 광고주의 브라우저가 제공한 원본 파일명. 관리자 UI 표시를 위해 유지;
     * 파일시스템 작업에는 절대 사용되지 않음.
     */
    @Column(name = "original_name", nullable = false, length = 255)
    val originalName: String,

    /**
     * multipart 업로드가 보고한 MIME 타입(예: `video/mp4`). 스트리밍
     * 엔드포인트가 매 요청마다 재감지하지 않고 `Content-Type`을 설정할 수
     * 있도록 저장됨.
     */
    @Column(name = "mime_type", nullable = false, length = 100)
    val mimeType: String,

    /**
     * 파일 크기(바이트). HTTP Range 응답에 필요함.
     */
    @Column(name = "size_bytes", nullable = false)
    val sizeBytes: Long,

    /**
     * 서버 파일시스템에서 비디오가 저장된 절대 경로. 업로드 시
     * `adsignage.video-storage-path`에 대해 해석됨.
     */
    @Column(name = "storage_path", nullable = false, length = 1024)
    val storagePath: String,

    @Column(name = "uploaded_at", nullable = false, updatable = false)
    val uploadedAt: Instant = Instant.now(),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Video) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}
