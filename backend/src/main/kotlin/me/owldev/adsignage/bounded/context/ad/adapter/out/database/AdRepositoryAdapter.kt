package me.owldev.adsignage.bounded.context.ad.adapter.out.database

import me.owldev.adsignage.bounded.context.ad.application.port.out.database.AdRepositoryPort
import me.owldev.adsignage.bounded.context.ad.domain.model.Ad
import org.springframework.stereotype.Repository

@Repository
class AdRepositoryAdapter(
    private val adRepository: AdRepository,
) : AdRepositoryPort {
    override fun save(ad: Ad): Ad = adRepository.save(ad)
    override fun findById(id: String): Ad? = adRepository.findById(id).orElse(null)
    override fun findAll(): List<Ad> = adRepository.findAll()
    override fun findAllById(ids: Iterable<String>): List<Ad> = adRepository.findAllById(ids)
    override fun findByIdAndAdvertiserId(id: String, advertiserId: String): Ad? =
        adRepository.findByIdAndAdvertiserId(id, advertiserId).orElse(null)
    override fun findAllByAdvertiserIdOrderByCreatedAtDesc(advertiserId: String): List<Ad> =
        adRepository.findAllByAdvertiserIdOrderByCreatedAtDesc(advertiserId)
    override fun delete(ad: Ad) { adRepository.delete(ad) }
}
