package me.owldev.adsignage.bounded.context.advertiser.adapter.out.database

import me.owldev.adsignage.bounded.context.advertiser.domain.model.Advertiser
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.Optional

@Repository
interface AdvertiserRepository : JpaRepository<Advertiser, String> {
    fun findByEmail(email: String): Optional<Advertiser>
    fun existsByEmail(email: String): Boolean
}
