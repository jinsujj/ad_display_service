package me.owldev.adsignage.bounded.context.ad.application.service

import me.owldev.adsignage.bounded.context.ad.application.port.out.database.AdRepositoryPort
import me.owldev.adsignage.bounded.context.ad.domain.exception.AdNotFoundException
import me.owldev.adsignage.bounded.context.ad.domain.exception.InvalidScheduleException
import me.owldev.adsignage.bounded.context.ad.domain.model.Ad
import me.owldev.adsignage.bounded.context.playlist.adapter.out.sse.PlaylistUpdatedEvent
import me.owldev.adsignage.bounded.context.queue.application.port.out.database.DeviceAdQueueRepositoryPort
import me.owldev.adsignage.bounded.context.queue.domain.model.DeviceAdQueue
import me.owldev.adsignage.bounded.context.queue.domain.model.DeviceAdQueueId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.context.ApplicationEvent
import org.springframework.context.ApplicationEventPublisher
import java.time.LocalDate
import java.time.LocalTime
import java.util.concurrent.CopyOnWriteArrayList

/**
 * [AdService] 단위 테스트 — 헥사고날 마이그레이션 후 추출된 mutating
 * 라이프사이클의 핵심 동작 검증.
 *
 *  - create:        팩토리 검증 + PLAYLIST_UPDATE 이벤트 발행
 *  - updateSchedule: 소유권 검사 (다른 광고주 → 404), 도메인 룰 위반 → 400
 *  - delete:        cascade 큐 정리 + 이벤트 발행
 *  - findOwned:     읽기 + 소유권 검사
 *
 * Cross-context 의존(queue port) 도 in-memory fake 로 직접 검증 — 큐 cascade 가
 * 실제로 일어나는지가 핵심.
 */
class AdServiceTest {

    private lateinit var ads: InMemoryAdRepository
    private lateinit var queues: InMemoryQueueRepository
    private lateinit var publisher: RecordingPublisher
    private lateinit var service: AdService

    private val ownerId = "adv-1"
    private val otherId = "adv-2"

    @BeforeEach
    fun setup() {
        ads = InMemoryAdRepository()
        queues = InMemoryQueueRepository()
        publisher = RecordingPublisher()
        service = AdService(ads, publisher, queues)
    }

    @Test
    fun `create persists ad and publishes PlaylistUpdatedEvent`() {
        val saved = service.create(
            advertiserId = ownerId,
            title = "Promo",
            videoFilename = "promo.mp4",
            startTime = LocalTime.of(9, 0),
            endTime = LocalTime.of(22, 0),
            dailyPlayCount = 50,
            campaignStartDate = LocalDate.now(),
            campaignEndDate = LocalDate.now().plusDays(7),
        )

        assertThat(saved.advertiserId).isEqualTo(ownerId)
        assertThat(ads.saved).hasSize(1)
        val events = publisher.events.filterIsInstance<PlaylistUpdatedEvent>()
        assertThat(events).hasSize(1)
        assertThat(events.single().adId).isEqualTo(saved.id)
    }

    @Test
    fun `create rejects endTime not after startTime via Ad factory`() {
        val ex = assertThrows<InvalidScheduleException> {
            service.create(
                advertiserId = ownerId,
                title = "Bad",
                videoFilename = "b.mp4",
                startTime = LocalTime.of(10, 0),
                endTime = LocalTime.of(10, 0), // == startTime
                dailyPlayCount = 10,
                campaignStartDate = LocalDate.now(),
                campaignEndDate = LocalDate.now().plusDays(1),
            )
        }
        assertThat(ex.fieldErrors).containsKey("endTime")
        assertThat(ads.saved).isEmpty()
        assertThat(publisher.events).isEmpty()
    }

    @Test
    fun `updateSchedule changes fields and publishes event`() {
        val ad = service.create(
            advertiserId = ownerId, title = "T", videoFilename = "v.mp4",
            startTime = LocalTime.of(9, 0), endTime = LocalTime.of(17, 0),
            dailyPlayCount = 50,
            campaignStartDate = LocalDate.now(), campaignEndDate = LocalDate.now().plusDays(7),
        )
        publisher.events.clear()

        val updated = service.updateSchedule(
            adId = ad.id,
            advertiserId = ownerId,
            startTime = LocalTime.of(8, 0),
            endTime = LocalTime.of(20, 0),
            dailyPlayCount = 100,
        )

        assertThat(updated.startTime).isEqualTo(LocalTime.of(8, 0))
        assertThat(updated.endTime).isEqualTo(LocalTime.of(20, 0))
        assertThat(updated.dailyPlayCount).isEqualTo(100)
        assertThat(publisher.events.filterIsInstance<PlaylistUpdatedEvent>()).hasSize(1)
    }

    @Test
    fun `updateSchedule by non-owner is mapped to AdNotFound (auth-and-isolation)`() {
        val ad = service.create(
            advertiserId = ownerId, title = "T", videoFilename = "v.mp4",
            startTime = LocalTime.of(9, 0), endTime = LocalTime.of(17, 0),
            dailyPlayCount = 50,
            campaignStartDate = LocalDate.now(), campaignEndDate = LocalDate.now().plusDays(7),
        )

        // 크로스 광고주 id 추측은 404 로 통일 — 존재 여부조차 누설하지 않음.
        val ex = assertThrows<AdNotFoundException> {
            service.updateSchedule(
                adId = ad.id,
                advertiserId = otherId,
                startTime = LocalTime.of(8, 0),
                endTime = LocalTime.of(20, 0),
                dailyPlayCount = 100,
            )
        }
        assertThat(ex.adId).isEqualTo(ad.id)
    }

    @Test
    fun `delete removes ad row plus all queue rows referencing it`() {
        val ad = service.create(
            advertiserId = ownerId, title = "T", videoFilename = "v.mp4",
            startTime = LocalTime.of(9, 0), endTime = LocalTime.of(17, 0),
            dailyPlayCount = 50,
            campaignStartDate = LocalDate.now(), campaignEndDate = LocalDate.now().plusDays(7),
        )
        // 운영자가 두 디바이스 큐에 담아 둠.
        queues.add(deviceId = "device-1", adId = ad.id)
        queues.add(deviceId = "device-2", adId = ad.id)
        publisher.events.clear()

        service.delete(ad.id, ownerId)

        assertThat(ads.saved).isEmpty()
        assertThat(queues.findAllByIdAdId(ad.id)).isEmpty()
        assertThat(publisher.events.filterIsInstance<PlaylistUpdatedEvent>()).hasSize(1)
    }

    @Test
    fun `delete by non-owner does not touch the ad`() {
        val ad = service.create(
            advertiserId = ownerId, title = "T", videoFilename = "v.mp4",
            startTime = LocalTime.of(9, 0), endTime = LocalTime.of(17, 0),
            dailyPlayCount = 50,
            campaignStartDate = LocalDate.now(), campaignEndDate = LocalDate.now().plusDays(7),
        )

        assertThrows<AdNotFoundException> { service.delete(ad.id, otherId) }
        assertThat(ads.saved).hasSize(1) // 그대로
    }

    @Test
    fun `findOwned returns ad when caller owns it`() {
        val ad = service.create(
            advertiserId = ownerId, title = "T", videoFilename = "v.mp4",
            startTime = LocalTime.of(9, 0), endTime = LocalTime.of(17, 0),
            dailyPlayCount = 50,
            campaignStartDate = LocalDate.now(), campaignEndDate = LocalDate.now().plusDays(7),
        )

        val found = service.findOwned(ad.id, ownerId)
        assertThat(found.id).isEqualTo(ad.id)
    }

    @Test
    fun `findOwned by non-owner throws AdNotFound`() {
        val ad = service.create(
            advertiserId = ownerId, title = "T", videoFilename = "v.mp4",
            startTime = LocalTime.of(9, 0), endTime = LocalTime.of(17, 0),
            dailyPlayCount = 50,
            campaignStartDate = LocalDate.now(), campaignEndDate = LocalDate.now().plusDays(7),
        )

        assertThrows<AdNotFoundException> { service.findOwned(ad.id, otherId) }
    }

    @Test
    fun `listOwned returns only caller's ads`() {
        service.create(
            advertiserId = ownerId, title = "T1", videoFilename = "v1.mp4",
            startTime = LocalTime.of(9, 0), endTime = LocalTime.of(17, 0), dailyPlayCount = 50,
            campaignStartDate = LocalDate.now(), campaignEndDate = LocalDate.now().plusDays(7),
        )
        service.create(
            advertiserId = otherId, title = "T2", videoFilename = "v2.mp4",
            startTime = LocalTime.of(9, 0), endTime = LocalTime.of(17, 0), dailyPlayCount = 50,
            campaignStartDate = LocalDate.now(), campaignEndDate = LocalDate.now().plusDays(7),
        )

        val mine = service.listOwned(ownerId)
        assertThat(mine).hasSize(1)
        assertThat(mine.single().title).isEqualTo("T1")
    }

    // -------- in-memory fakes --------

    private class InMemoryAdRepository : AdRepositoryPort {
        val saved: MutableList<Ad> = mutableListOf()
        override fun save(ad: Ad): Ad {
            saved.removeAll { it.id == ad.id }
            saved += ad
            return ad
        }
        override fun findById(id: String): Ad? = saved.firstOrNull { it.id == id }
        override fun findAll(): List<Ad> = saved.toList()
        override fun findAllById(ids: Iterable<String>): List<Ad> {
            val s = ids.toSet(); return saved.filter { it.id in s }
        }
        override fun findByIdAndAdvertiserId(id: String, advertiserId: String): Ad? =
            saved.firstOrNull { it.id == id && it.advertiserId == advertiserId }
        override fun findAllByAdvertiserIdOrderByCreatedAtDesc(advertiserId: String): List<Ad> =
            saved.filter { it.advertiserId == advertiserId }.sortedByDescending { it.createdAt }
        override fun findAllOrderByCreatedAtDesc(): List<Ad> =
            saved.sortedByDescending { it.createdAt }
        override fun delete(ad: Ad) { saved.removeAll { it.id == ad.id } }
    }

    private class InMemoryQueueRepository : DeviceAdQueueRepositoryPort {
        private val store: MutableList<DeviceAdQueue> = mutableListOf()
        fun add(deviceId: String, adId: String) {
            store += DeviceAdQueue(id = DeviceAdQueueId(deviceId, adId))
        }
        override fun save(queue: DeviceAdQueue): DeviceAdQueue { store += queue; return queue }
        override fun findById(id: DeviceAdQueueId): DeviceAdQueue? = store.firstOrNull { it.id == id }
        override fun findAll(): List<DeviceAdQueue> = store.toList()
        override fun findAllByIdDeviceIdOrderByAddedAtDesc(deviceId: String): List<DeviceAdQueue> =
            store.filter { it.id.deviceId == deviceId }.sortedByDescending { it.addedAt }
        override fun findAllByIdAdId(adId: String): List<DeviceAdQueue> =
            store.filter { it.id.adId == adId }
        override fun deleteOne(deviceId: String, adId: String): Int {
            val n = store.size; store.removeAll { it.id.deviceId == deviceId && it.id.adId == adId }
            return n - store.size
        }
        override fun deleteAllByDeviceId(deviceId: String): Int {
            val n = store.size; store.removeAll { it.id.deviceId == deviceId }
            return n - store.size
        }
        override fun deleteAllByAdId(adId: String): Int {
            val n = store.size; store.removeAll { it.id.adId == adId }
            return n - store.size
        }
    }

    private class RecordingPublisher : ApplicationEventPublisher {
        val events: CopyOnWriteArrayList<Any> = CopyOnWriteArrayList()
        override fun publishEvent(event: ApplicationEvent) { events += event }
        override fun publishEvent(event: Any) { events += event }
    }
}
