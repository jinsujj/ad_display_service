package me.owldev.adsignage.domain.restaurant

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "restaurants")
class Restaurant(
    @Id
    @Column(name = "restaurant_id", nullable = false, updatable = false, length = 36)
    val restaurantId: String,

    @Column(name = "name", nullable = false, length = 255)
    var name: String,

    @Column(name = "address", length = 255)
    var address: String? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
) {
    override fun equals(other: Any?): Boolean = other is Restaurant && other.restaurantId == restaurantId
    override fun hashCode(): Int = restaurantId.hashCode()
}
