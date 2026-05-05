package me.owldev.adsignage.bounded.context.video.application.port.out.storage

import me.owldev.adsignage.bounded.context.video.domain.dto.StoredVideo
import org.springframework.web.multipart.MultipartFile

/**
 * 업로드된 영상 파일을 백킹 스토어(로컬 디스크 / S3 / GCS 등)에 영속화하는
 * 외부 시스템 포트. database 가 아닌 storage 카테고리 — hair_d_heart 의
 * `application/port/out/storage/` 패턴.
 *
 * 인터페이스는 의도적으로 백엔드에 비종속 — 해커톤은 로컬 파일시스템
 * 구현(LocalVideoStorageAdapter)을 출시하지만, 동일한 계약을 추후 업로드
 * 컨트롤러나 [me.owldev.adsignage.bounded.context.video.domain.model.Video]
 * 엔터티를 건드리지 않고 S3 / GCS 로 백킹할 수 있다.
 */
interface VideoStoragePort {
    /**
     * [file]을 설정된 스토리지 루트 아래에 영속화한다.
     *
     * @throws IllegalArgumentException 업로드가 비어있거나, 사용 가능한
     *   원본 파일명이 없거나, 미지원 확장자일 때.
     */
    fun store(file: MultipartFile): StoredVideo
}
