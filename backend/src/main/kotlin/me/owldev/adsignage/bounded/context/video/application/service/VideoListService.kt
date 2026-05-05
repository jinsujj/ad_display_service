package me.owldev.adsignage.bounded.context.video.application.service

import me.owldev.adsignage.bounded.context.video.application.port.out.database.VideoRepositoryPort
import me.owldev.adsignage.bounded.context.video.domain.model.Video
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 광고주가 업로드한 비디오의 read-only 조회 서비스. auth-and-isolation 계약(AC 4)
 * 의 일환으로 호출 광고주가 업로드한 행만 반환한다.
 */
@Service
class VideoListService(
    private val videoRepositoryPort: VideoRepositoryPort,
) {
    @Transactional(readOnly = true)
    fun listOwned(advertiserId: String): List<Video> =
        videoRepositoryPort.findAllByAdvertiserIdOrderByUploadedAtDesc(advertiserId)
}
