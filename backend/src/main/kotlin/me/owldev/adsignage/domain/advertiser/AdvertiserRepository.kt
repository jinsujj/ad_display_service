package me.owldev.adsignage.domain.advertiser

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.Optional

@Repository
interface AdvertiserRepository : JpaRepository<Advertiser, String> {
    fun findByEmail(email: String): Optional<Advertiser>
    fun existsByEmail(email: String): Boolean
}
