package me.owldev.adsignage.domain.restaurant

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface RestaurantRepository : JpaRepository<Restaurant, String> {
    fun findAllByOrderByName(): List<Restaurant>
}
