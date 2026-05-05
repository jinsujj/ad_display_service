package me.owldev.adsignage.bounded.context.queue.domain.dto

import com.fasterxml.jackson.annotation.JsonFormat
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

data class AddAdToQueueRequest(
    @field:NotBlank(message = "adId must not be blank")
    @field:Size(max = 36, message = "adId must be at most 36 characters")
    val adId: String?,
)

data class AddAdToQueueResponse(
    val deviceId: String,
    val adId: String,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val addedAt: Instant,
    val created: Boolean,
)

data class QueuedAdItem(
    val adId: String,
    val title: String,
    val videoFilename: String,
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "HH:mm")
    val startTime: LocalTime,
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "HH:mm")
    val endTime: LocalTime,
    val dailyPlayCount: Int,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val campaignStartDate: LocalDate,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val campaignEndDate: LocalDate,
    val status: String,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val addedAt: Instant,
)
