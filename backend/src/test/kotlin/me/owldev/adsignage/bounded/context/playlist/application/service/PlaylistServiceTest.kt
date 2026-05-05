package me.owldev.adsignage.bounded.context.playlist.application.service

import me.owldev.adsignage.bounded.context.ad.application.port.out.database.AdRepositoryPort
import me.owldev.adsignage.bounded.context.ad.domain.model.Ad
import me.owldev.adsignage.bounded.context.assignment.application.port.out.database.DeviceAssignmentRepositoryPort
import me.owldev.adsignage.bounded.context.assignment.domain.model.DeviceAssignment
import me.owldev.adsignage.bounded.context.queue.application.port.out.database.DeviceAdQueueRepositoryPort
import me.owldev.adsignage.bounded.context.queue.domain.model.DeviceAdQueue
import me.owldev.adsignage.bounded.context.queue.domain.model.DeviceAdQueueId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * [PlaylistService] 단위 테스트.
 *
 * 헥사고날 컨벤션: 자체 entity/persistence 가 없는 컨텍스트의 service 는
 * 다른 컨텍스트의 port 만 합성. in-memory fake 3개로 통합 부담 없이 검증.
 *
 * 케이스:
 *  - 큐 비어 있음 → ads 빈 리스트, restaurantId 는 활성 매핑에서 그대로
 *  - 활성 매핑 없음 → restaurantId null
 *  - 큐 행 + 캠페인 ACTIVE 광고 → 응답에 그 광고 포함, scheduleId == adId
 *  - 큐 행이지만 광고가 EXPIRED → 응답에서 제외
 *  - 큐 행이지만 광고가 SCHEDULED → 응답에서 제외
 *  - 큐 행이지만 광고 자체가 없음(고아 큐) → 그 항목만 조용히 스킵
 */
class PlaylistServiceTest {

    private lateinit var ads: InMemoryAdRepository
    private lateinit var assignments: InMemoryAssignmentRepository
    private lateinit var queues: InMemoryQueueRepository
    private lateinit var service: PlaylistService

    private val deviceId = "device-001"
    private val restaurantId = "rest-A"

    @BeforeEach
    fun setup() {
        ads = InMemoryAdRepository()
        assignments = InMemoryAssignmentRepository()
        queues = InMemoryQueueRepository()
        service = PlaylistService(ads, assignments, queues)
    }

    @Test
    fun `empty queue returns empty ads with restaurantId from active assignment`() {
        assignments.save(
            DeviceAssignment(deviceId = deviceId, restaurantId = restaurantId),
        )

        val response = service.buildPlaylist(deviceId)

        assertThat(response.deviceId).isEqualTo(deviceId)
        assertThat(response.restaurantId).isEqualTo(restaurantId)
        assertThat(response.ads).isEmpty()
    }

    @Test
    fun `no active assignment yields null restaurantId`() {
        // 큐도 비어 있고 매핑도 없는 새 디바이스 — splash 화면용 빈 응답.
        val response = service.buildPlaylist(deviceId)

        assertThat(response.restaurantId).isNull()
        assertThat(response.ads).isEmpty()
    }

    @Test
    fun `queued ACTIVE ad appears in playlist with videoUrl prefix`() {
        val ad = newAd(adId = "ad-1", days = -1L to 30L)
        ads.saved += ad
        queues.add(deviceId, ad.id)
        assignments.save(DeviceAssignment(deviceId = deviceId, restaurantId = restaurantId))

        val response = service.buildPlaylist(deviceId)

        assertThat(response.ads).hasSize(1)
        val item = response.ads.single()
        assertThat(item.adId).isEqualTo("ad-1")
        assertThat(item.scheduleId).isEqualTo("ad-1") // 1:1 임베드
        assertThat(item.videoUrl).isEqualTo("/api/videos/${ad.videoFilename}")
        assertThat(item.dailyCount).isEqualTo(ad.dailyPlayCount)
    }

    @Test
    fun `queued but EXPIRED ad is filtered out`() {
        // 어제 끝난 캠페인 — computeStatus() 가 EXPIRED 반환.
        val ad = newAd(adId = "ad-old", days = -10L to -1L)
        ads.saved += ad
        queues.add(deviceId, ad.id)

        val response = service.buildPlaylist(deviceId)

        assertThat(response.ads).isEmpty()
    }

    @Test
    fun `queued but SCHEDULED ad is filtered out`() {
        // 내일 시작 — 아직 SCHEDULED.
        val ad = newAd(adId = "ad-future", days = 1L to 10L)
        ads.saved += ad
        queues.add(deviceId, ad.id)

        val response = service.buildPlaylist(deviceId)

        assertThat(response.ads).isEmpty()
    }

    @Test
    fun `orphan queue row referencing missing ad is silently skipped`() {
        // 운영자가 광고를 큐에 담은 뒤 광고 자체가 삭제된 경우 — 큐 행은
        // 남았지만 ads 에 없음. 빈 ads 로 떨어지지 NPE 가 아님.
        queues.add(deviceId, "deleted-ad")

        val response = service.buildPlaylist(deviceId)

        assertThat(response.ads).isEmpty()
    }

    @Test
    fun `multiple queued ads — only ACTIVE are returned in queue order`() {
        val active = newAd(adId = "ad-active", days = -1L to 30L)
        val expired = newAd(adId = "ad-expired", days = -10L to -1L)
        ads.saved += active
        ads.saved += expired
        queues.add(deviceId, active.id)
        queues.add(deviceId, expired.id)

        val response = service.buildPlaylist(deviceId)

        assertThat(response.ads.map { it.adId }).containsExactly("ad-active")
    }

    private fun newAd(adId: String, days: Pair<Long, Long>): Ad {
        val today = LocalDate.now()
        return Ad(
            id = adId,
            advertiserId = "adv-1",
            title = "T-$adId",
            videoFilename = "$adId.mp4",
            startTime = LocalTime.of(9, 0),
            endTime = LocalTime.of(22, 0),
            dailyPlayCount = 50,
            campaignStartDate = today.plusDays(days.first),
            campaignEndDate = today.plusDays(days.second),
        )
    }

    // -------- in-memory fakes (port 만 구현) --------

    private class InMemoryAdRepository : AdRepositoryPort {
        val saved: MutableList<Ad> = mutableListOf()
        override fun save(ad: Ad): Ad = ad.also { saved += it }
        override fun findById(id: String): Ad? = saved.firstOrNull { it.id == id }
        override fun findAll(): List<Ad> = saved.toList()
        override fun findAllById(ids: Iterable<String>): List<Ad> {
            val s = ids.toSet()
            return saved.filter { it.id in s }
        }
        override fun findByIdAndAdvertiserId(id: String, advertiserId: String): Ad? =
            saved.firstOrNull { it.id == id && it.advertiserId == advertiserId }
        override fun findAllByAdvertiserIdOrderByCreatedAtDesc(advertiserId: String): List<Ad> =
            saved.filter { it.advertiserId == advertiserId }.sortedByDescending { it.createdAt }
        override fun delete(ad: Ad) { saved.removeAll { it.id == ad.id } }
    }

    private class InMemoryAssignmentRepository : DeviceAssignmentRepositoryPort {
        private val store: MutableList<DeviceAssignment> = mutableListOf()
        override fun save(assignment: DeviceAssignment): DeviceAssignment {
            store += assignment
            return assignment
        }
        override fun findByDeviceIdAndActiveTrue(deviceId: String): DeviceAssignment? =
            store.firstOrNull { it.deviceId == deviceId && it.active }
        override fun findAllByActiveTrue(): List<DeviceAssignment> = store.filter { it.active }
        override fun findAllByRestaurantIdAndActiveTrue(restaurantId: String): List<DeviceAssignment> =
            store.filter { it.restaurantId == restaurantId && it.active }
        override fun findAllByDeviceIdOrderByAssignedAtDesc(deviceId: String): List<DeviceAssignment> =
            store.filter { it.deviceId == deviceId }.sortedByDescending { it.assignedAt }
        override fun deactivateCurrentForDevice(deviceId: String): Int {
            val active = store.filter { it.deviceId == deviceId && it.active }
            active.forEach { it.deactivate() }
            return active.size
        }
        override fun deleteAllByDeviceId(deviceId: String): Int {
            val n = store.count { it.deviceId == deviceId }
            store.removeAll { it.deviceId == deviceId }
            return n
        }
    }

    private class InMemoryQueueRepository : DeviceAdQueueRepositoryPort {
        private val store: MutableList<DeviceAdQueue> = mutableListOf()
        fun add(deviceId: String, adId: String) {
            store += DeviceAdQueue(id = DeviceAdQueueId(deviceId, adId), addedAt = Instant.now())
        }
        override fun save(queue: DeviceAdQueue): DeviceAdQueue { store += queue; return queue }
        override fun findById(id: DeviceAdQueueId): DeviceAdQueue? = store.firstOrNull { it.id == id }
        override fun findAll(): List<DeviceAdQueue> = store.toList()
        override fun findAllByIdDeviceIdOrderByAddedAtDesc(deviceId: String): List<DeviceAdQueue> =
            store.filter { it.id.deviceId == deviceId }.sortedByDescending { it.addedAt }
        override fun findAllByIdAdId(adId: String): List<DeviceAdQueue> =
            store.filter { it.id.adId == adId }
        override fun deleteOne(deviceId: String, adId: String): Int {
            val before = store.size
            store.removeAll { it.id.deviceId == deviceId && it.id.adId == adId }
            return before - store.size
        }
        override fun deleteAllByDeviceId(deviceId: String): Int {
            val before = store.size
            store.removeAll { it.id.deviceId == deviceId }
            return before - store.size
        }
        override fun deleteAllByAdId(adId: String): Int {
            val before = store.size
            store.removeAll { it.id.adId == adId }
            return before - store.size
        }
    }
}
