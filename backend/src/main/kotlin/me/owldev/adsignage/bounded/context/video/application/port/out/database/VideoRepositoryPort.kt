package me.owldev.adsignage.bounded.context.video.application.port.out.database

import me.owldev.adsignage.bounded.context.video.domain.model.Video

/**
 * Video 컨텍스트의 영속 포트. 서비스(업로드/리스트/스트리밍)와 외부
 * 컨텍스트가 이 포트만 들이도록 통일.
 */
interface VideoRepositoryPort {
    fun save(video: Video): Video
    fun findById(id: String): Video?
    fun findByFilename(filename: String): Video?
    fun existsByFilename(filename: String): Boolean
    fun findAllByAdvertiserIdOrderByUploadedAtDesc(advertiserId: String): List<Video>
    fun findByIdAndAdvertiserId(id: String, advertiserId: String): Video?
}
