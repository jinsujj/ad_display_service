package me.owldev.adsignage.bounded.context.advertiser.application.port.out.database

import me.owldev.adsignage.bounded.context.advertiser.domain.model.Advertiser

/**
 * Advertiser 컨텍스트의 영속 포트. 외부 컨텍스트(특히 auth)가 광고주 계정을
 * 조회/저장할 때 이 포트만 의존하도록 통일한다 — Spring Data 인터페이스를
 * 직접 들이지 않음으로써 컨텍스트 간 결합 깊이를 한 단계 묶어 둔다.
 */
interface AdvertiserRepositoryPort {
    fun save(advertiser: Advertiser): Advertiser
    fun findById(id: String): Advertiser?
    fun findByEmail(email: String): Advertiser?
    fun existsByEmail(email: String): Boolean
}
