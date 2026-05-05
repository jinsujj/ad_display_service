package me.owldev.adsignage.bounded.context.restaurant.application.service

import me.owldev.adsignage.bounded.context.restaurant.application.port.out.database.RestaurantRepositoryPort
import me.owldev.adsignage.bounded.context.restaurant.domain.model.Restaurant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * [RestaurantService] 단위 테스트.
 *
 * 단순 read-only 서비스라 in-memory port fake 하나로 충분.
 *  - listAll() 이 RestaurantListItem DTO 로 매핑
 *  - 빈 저장소면 빈 리스트
 *  - 정렬은 port 의 `findAllByOrderByName()` 가 책임 (서비스가 재정렬 안 함)
 */
class RestaurantServiceTest {

    @Test
    fun `listAll maps entities to RestaurantListItem`() {
        val repo = InMemoryRestaurantRepository().apply {
            saved += Restaurant(restaurantId = "r-1", name = "Alpha", address = "addr-1")
            saved += Restaurant(restaurantId = "r-2", name = "Beta", address = null)
        }
        val service = RestaurantService(repo)

        val items = service.listAll()

        assertThat(items).hasSize(2)
        assertThat(items[0].restaurantId).isEqualTo("r-1")
        assertThat(items[0].restaurantName).isEqualTo("Alpha")
        assertThat(items[0].address).isEqualTo("addr-1")
        assertThat(items[1].restaurantName).isEqualTo("Beta")
        assertThat(items[1].address).isNull()
    }

    @Test
    fun `listAll on empty repo returns empty list`() {
        val service = RestaurantService(InMemoryRestaurantRepository())
        assertThat(service.listAll()).isEmpty()
    }

    @Test
    fun `listAll relies on port's name-ordered query (no in-service re-sort)`() {
        // port 가 이미 정렬을 약속하므로 service 는 그대로 매핑만. 입력 순서를
        // 그대로 유지하는지로 검증.
        val repo = InMemoryRestaurantRepository().apply {
            saved += Restaurant(restaurantId = "z", name = "Zebra")
            saved += Restaurant(restaurantId = "a", name = "Apple")
        }
        val service = RestaurantService(repo)

        assertThat(service.listAll().map { it.restaurantName })
            .containsExactly("Zebra", "Apple")
    }

    private class InMemoryRestaurantRepository : RestaurantRepositoryPort {
        val saved: MutableList<Restaurant> = mutableListOf()
        override fun findAll(): List<Restaurant> = saved.toList()
        override fun findAllByOrderByName(): List<Restaurant> = saved.toList()
        override fun existsById(restaurantId: String): Boolean =
            saved.any { it.restaurantId == restaurantId }
    }
}
