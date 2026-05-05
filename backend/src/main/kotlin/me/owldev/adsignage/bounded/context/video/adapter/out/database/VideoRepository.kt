package me.owldev.adsignage.bounded.context.video.adapter.out.database

import me.owldev.adsignage.bounded.context.video.domain.model.Video
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.Optional

/**
 * [Video]를 위한 Spring Data JPA repository.
 *
 * "and advertiserId" 변형 노출 — auth-and-isolation 계약(AC 4)은 광고주가
 * 자신의 비디오만 보도록 강제. 술어를 쿼리에 푸시하면 크로스 광고주 id
 * 추측이 `Optional.empty()`를 반환.
 */
@Repository
interface VideoRepository : JpaRepository<Video, String> {
    fun findByFilename(filename: String): Optional<Video>
    fun existsByFilename(filename: String): Boolean
    fun findAllByAdvertiserIdOrderByUploadedAtDesc(advertiserId: String): List<Video>
    fun findByIdAndAdvertiserId(id: String, advertiserId: String): Optional<Video>
}
