package me.owldev.adsignage.bounded.context.video.domain.dto

import com.fasterxml.jackson.annotation.JsonInclude
import me.owldev.adsignage.bounded.context.video.domain.model.Video
import java.time.Instant

/**
 * `POST /api/videos`가 반환하는 [Video]의 와이어 레벨 표현.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class VideoResponse(
    val id: String,
    val filename: String,
    val originalName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val url: String,
    val uploadedAt: Instant,
) {
    companion object {
        /** 스트리밍 엔드포인트가 위치하는 정식 URL 접두사. */
        private const val URL_PREFIX = "/api/videos"

        fun from(entity: Video): VideoResponse = VideoResponse(
            id = entity.id,
            filename = entity.filename,
            originalName = entity.originalName,
            mimeType = entity.mimeType,
            sizeBytes = entity.sizeBytes,
            url = "$URL_PREFIX/${entity.filename}",
            uploadedAt = entity.uploadedAt,
        )
    }
}

/**
 * 성공한 업로드의 결과 — 호출자가 [Video] 엔터티를 구성하고 *그리고*
 * 플레이어 플레이리스트에 영상을 노출하는 데 필요한 모든 필드.
 */
data class StoredVideo(
    val filename: String,
    val originalName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val storagePath: String,
    val urlPath: String,
)
