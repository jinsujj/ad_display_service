package me.owldev.adsignage.bounded.context.advertiser.adapter.out.database

import me.owldev.adsignage.bounded.context.advertiser.application.port.out.database.AdvertiserRepositoryPort
import me.owldev.adsignage.bounded.context.advertiser.domain.model.Advertiser
import org.springframework.stereotype.Repository

@Repository
class AdvertiserRepositoryAdapter(
    private val advertiserRepository: AdvertiserRepository,
) : AdvertiserRepositoryPort {
    override fun save(advertiser: Advertiser): Advertiser = advertiserRepository.save(advertiser)
    override fun findById(id: String): Advertiser? = advertiserRepository.findById(id).orElse(null)
    override fun findByEmail(email: String): Advertiser? = advertiserRepository.findByEmail(email).orElse(null)
    override fun existsByEmail(email: String): Boolean = advertiserRepository.existsByEmail(email)
}
