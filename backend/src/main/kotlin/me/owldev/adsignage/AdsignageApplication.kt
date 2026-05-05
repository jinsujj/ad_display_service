package me.owldev.adsignage

import me.owldev.adsignage.auth.jwt.JwtProperties
import me.owldev.adsignage.bounded.context.video.config.VideoStorageProperties
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableConfigurationProperties(
    JwtProperties::class,
    VideoStorageProperties::class,
)
// SseEmitterRegistry 의 keepalive 같은 @Scheduled 백그라운드 작업이 동작하려면
// 명시 활성화 필요. 켜지 않으면 phantom SSE 연결이 청소되지 않는다.
@EnableScheduling
class AdsignageApplication

fun main(args: Array<String>) {
    runApplication<AdsignageApplication>(*args)
}
