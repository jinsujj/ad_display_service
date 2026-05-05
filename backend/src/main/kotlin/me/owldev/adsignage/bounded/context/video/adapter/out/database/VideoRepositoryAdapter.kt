package me.owldev.adsignage.bounded.context.video.adapter.out.database

import me.owldev.adsignage.bounded.context.video.application.port.out.database.VideoRepositoryPort
import me.owldev.adsignage.bounded.context.video.domain.model.Video
import org.springframework.stereotype.Repository

@Repository
class VideoRepositoryAdapter(
    private val videoRepository: VideoRepository,
) : VideoRepositoryPort {
    override fun save(video: Video): Video = videoRepository.save(video)
    override fun findById(id: String): Video? = videoRepository.findById(id).orElse(null)
    override fun findByFilename(filename: String): Video? = videoRepository.findByFilename(filename).orElse(null)
    override fun existsByFilename(filename: String): Boolean = videoRepository.existsByFilename(filename)
    override fun findAllByAdvertiserIdOrderByUploadedAtDesc(advertiserId: String): List<Video> =
        videoRepository.findAllByAdvertiserIdOrderByUploadedAtDesc(advertiserId)
    override fun findByIdAndAdvertiserId(id: String, advertiserId: String): Video? =
        videoRepository.findByIdAndAdvertiserId(id, advertiserId).orElse(null)
}
