package me.owldev.adsignage

import me.owldev.adsignage.auth.jwt.JwtProperties
import me.owldev.adsignage.domain.video.storage.VideoStorageProperties
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication

@SpringBootApplication
@EnableConfigurationProperties(
    JwtProperties::class,
    VideoStorageProperties::class,
)
class AdsignageApplication

fun main(args: Array<String>) {
    runApplication<AdsignageApplication>(*args)
}
