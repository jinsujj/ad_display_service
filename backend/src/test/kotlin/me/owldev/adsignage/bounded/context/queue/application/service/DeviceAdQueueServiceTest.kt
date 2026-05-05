package me.owldev.adsignage.bounded.context.queue.application.service

import me.owldev.adsignage.bounded.context.ad.application.port.out.database.AdRepositoryPort
import me.owldev.adsignage.bounded.context.ad.domain.model.Ad
import me.owldev.adsignage.bounded.context.device.application.port.out.database.DeviceRepositoryPort
import me.owldev.adsignage.bounded.context.device.domain.model.Device
import me.owldev.adsignage.bounded.context.playlist.adapter.out.sse.PlaylistUpdatedEvent
import me.owldev.adsignage.bounded.context.queue.application.port.out.database.DeviceAdQueueRepositoryPort
import me.owldev.adsignage.bounded.context.queue.domain.model.DeviceAdQueue
import me.owldev.adsignage.bounded.context.queue.domain.model.DeviceAdQueueId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEvent
import org.springframework.context.ApplicationEventPublisher
import java.time.LocalDate
import java.time.LocalTime
import java.util.concurrent.CopyOnWriteArrayList

/**
 * [DeviceAdQueueService] 단위 테스트.
 *
 * 멱등 add/remove + PLAYLIST_UPDATE SSE 이벤트 발행이 핵심 계약.
 *  - listForDevice: device 가 없으면 null (404), 큐 비면 빈 리스트, 큐 + ad 매핑
 *  - addToQueue:    device/ad 존재 검증 → 없으면 null, 신규 추가는 created=true + 이벤트
 *  - addToQueue:    이미 큐에 있으면 멱등 (created=false), 이벤트 발행 없음
 *  - removeFromQueue: 0/1 행 모두 멱등, 1개라도 지웠을 때만 이벤트
 */
class DeviceAdQueueServiceTest {

    private lateinit var queues: InMemoryQueueRepository
    private lateinit var devices: InMemoryDeviceRepository
    private lateinit var ads: InMemoryAdRepository
    private lateinit var publisher: RecordingPublisher
    private lateinit var service: DeviceAdQueueService

    private val deviceId = "device-001"
    private val adId = "ad-1"
    private val advertiserId = "adv-1"

    @BeforeEach
    fun setup() {
        queues = InMemoryQueueRepository()
        devices = InMemoryDeviceRepository().apply {
            saved += Device(deviceId = deviceId, deviceName = "Test")
        }
        ads = InMemoryAdRepository().apply {
            saved += newAd(adId = adId, advertiserId = advertiserId)
        }
        publisher = RecordingPublisher()
        service = DeviceAdQueueService(queues, devices, ads, publisher)
    }

    @Test
    fun `listForDevice returns null when device unknown`() {
        assertThat(service.listForDevice("ghost-device")).isNull()
    }

    @Test
    fun `listForDevice returns empty list when queue empty`() {
        assertThat(service.listForDevice(deviceId)).isEqualTo(emptyList<Any>())
    }

    @Test
    fun `listForDevice maps queue rows to QueuedAdItem with current status`() {
        queues.put(deviceId, adId)
        val items = service.listForDevice(deviceId)!!
        assertThat(items).hasSize(1)
        assertThat(items.single().adId).isEqualTo(adId)
        assertThat(items.single().status).isEqualTo("ACTIVE")
    }

    @Test
    fun `addToQueue returns null when device unknown`() {
        assertThat(service.addToQueue("ghost-device", adId)).isNull()
        assertThat(publisher.events).isEmpty()
    }

    @Test
    fun `addToQueue returns null when ad unknown`() {
        assertThat(service.addToQueue(deviceId, "ghost-ad")).isNull()
        assertThat(publisher.events).isEmpty()
    }

    @Test
    fun `addToQueue inserts new row and publishes PLAYLIST_UPDATE`() {
        val response = service.addToQueue(deviceId, adId)!!
        assertThat(response.created).isTrue
        assertThat(response.deviceId).isEqualTo(deviceId)
        assertThat(response.adId).isEqualTo(adId)
        assertThat(queues.findAll()).hasSize(1)
        assertThat(publisher.events.filterIsInstance<PlaylistUpdatedEvent>()).hasSize(1)
    }

    @Test
    fun `addToQueue is idempotent — second call returns created=false and emits no event`() {
        service.addToQueue(deviceId, adId)
        publisher.events.clear()

        val response = service.addToQueue(deviceId, adId)!!

        assertThat(response.created).isFalse
        assertThat(queues.findAll()).hasSize(1)
        assertThat(publisher.events).isEmpty()
    }

    @Test
    fun `removeFromQueue deletes row and publishes event`() {
        queues.put(deviceId, adId)
        publisher.events.clear()

        val removed = service.removeFromQueue(deviceId, adId)

        assertThat(removed).isEqualTo(1)
        assertThat(queues.findAll()).isEmpty()
        assertThat(publisher.events.filterIsInstance<PlaylistUpdatedEvent>()).hasSize(1)
    }

    @Test
    fun `removeFromQueue is idempotent — missing row returns 0 and emits no event`() {
        val removed = service.removeFromQueue(deviceId, adId)
        assertThat(removed).isEqualTo(0)
        assertThat(publisher.events).isEmpty()
    }

    private fun newAd(adId: String, advertiserId: String): Ad = Ad(
        id = adId,
        advertiserId = advertiserId,
        title = "T",
        videoFilename = "$adId.mp4",
        startTime = LocalTime.of(9, 0),
        endTime = LocalTime.of(22, 0),
        dailyPlayCount = 50,
        campaignStartDate = LocalDate.now().minusDays(1),
        campaignEndDate = LocalDate.now().plusDays(30),
    )

    // -------- in-memory fakes --------

    private class InMemoryQueueRepository : DeviceAdQueueRepositoryPort {
        private val store: MutableList<DeviceAdQueue> = mutableListOf()
        fun put(deviceId: String, adId: String) {
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

    private class InMemoryDeviceRepository : DeviceRepositoryPort {
        val saved: MutableList<Device> = mutableListOf()
        override fun save(device: Device): Device {
            saved.removeAll { it.deviceId == device.deviceId }
            saved += device
            return device
        }
        override fun findById(deviceId: String): Device? = saved.firstOrNull { it.deviceId == deviceId }
        override fun findAllById(deviceIds: Iterable<String>): List<Device> {
            val s = deviceIds.toSet(); return saved.filter { it.deviceId in s }
        }
        override fun findAllByOrderByRegisteredAtDesc(): List<Device> = saved.sortedByDescending { it.registeredAt }
        override fun existsById(deviceId: String): Boolean = saved.any { it.deviceId == deviceId }
        override fun deleteById(deviceId: String) { saved.removeAll { it.deviceId == deviceId } }
    }

    private class InMemoryAdRepository : AdRepositoryPort {
        val saved: MutableList<Ad> = mutableListOf()
        override fun save(ad: Ad): Ad { saved += ad; return ad }
        override fun findById(id: String): Ad? = saved.firstOrNull { it.id == id }
        override fun findAll(): List<Ad> = saved.toList()
        override fun findAllById(ids: Iterable<String>): List<Ad> {
            val s = ids.toSet(); return saved.filter { it.id in s }
        }
        override fun findByIdAndAdvertiserId(id: String, advertiserId: String): Ad? =
            saved.firstOrNull { it.id == id && it.advertiserId == advertiserId }
        override fun findAllByAdvertiserIdOrderByCreatedAtDesc(advertiserId: String): List<Ad> =
            saved.filter { it.advertiserId == advertiserId }
        override fun findAllOrderByCreatedAtDesc(): List<Ad> =
            saved.sortedByDescending { it.createdAt }
        override fun delete(ad: Ad) { saved.removeAll { it.id == ad.id } }
    }

    private class RecordingPublisher : ApplicationEventPublisher {
        val events: CopyOnWriteArrayList<Any> = CopyOnWriteArrayList()
        override fun publishEvent(event: ApplicationEvent) { events += event }
        override fun publishEvent(event: Any) { events += event }
    }
}
