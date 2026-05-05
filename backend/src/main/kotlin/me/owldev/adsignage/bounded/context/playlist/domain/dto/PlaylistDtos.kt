package me.owldev.adsignage.bounded.context.playlist.domain.dto

import com.fasterxml.jackson.annotation.JsonFormat
import java.time.Instant
import java.time.LocalTime

data class DevicePlaylistResponse(
    val deviceId: String,
    val restaurantId: String?,
    val ads: List<PlaylistAdResponse>,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val fetchedAt: Instant,
)

data class PlaylistAdResponse(
    val adId: String,
    val title: String,
    val videoUrl: String,
    val scheduleId: String,
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "HH:mm")
    val startTime: LocalTime,
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "HH:mm")
    val endTime: LocalTime,
    val dailyCount: Int,
)
