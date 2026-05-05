package me.owldev.adsignage.bounded.context.ad.application.port.out.database

import me.owldev.adsignage.bounded.context.ad.domain.model.Ad

/**
 * Ad 컨텍스트의 영속 포트. 자기 컨텍스트 service 와 외부 컨텍스트
 * (queue / device / playlist) 모두 이 포트를 통해서만 광고를 읽고 쓴다 —
 * Spring Data 인터페이스를 직접 들이지 않음으로써 컨텍스트 간 결합 깊이를
 * 한 단계 묶어 둔다.
 *
 * "and advertiserId" 변형을 의도적으로 노출 — auth-and-isolation 계약(AC 4)은
 * 광고주가 자신의 광고만 보고/수정할 수 있다는 것이며, 술어를 쿼리에 푸시
 * 하면 크로스 광고주 id 추측이 null 을 반환하고 호출자에게 404 를 건넨다.
 */
interface AdRepositoryPort {
    fun save(ad: Ad): Ad
    fun findById(id: String): Ad?
    fun findAll(): List<Ad>
    fun findAllById(ids: Iterable<String>): List<Ad>
    fun findByIdAndAdvertiserId(id: String, advertiserId: String): Ad?
    fun findAllByAdvertiserIdOrderByCreatedAtDesc(advertiserId: String): List<Ad>
    fun delete(ad: Ad)
}
