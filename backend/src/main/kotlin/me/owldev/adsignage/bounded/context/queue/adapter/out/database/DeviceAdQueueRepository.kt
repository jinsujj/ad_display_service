package me.owldev.adsignage.bounded.context.queue.adapter.out.database

import me.owldev.adsignage.bounded.context.queue.domain.model.DeviceAdQueue
import me.owldev.adsignage.bounded.context.queue.domain.model.DeviceAdQueueId
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface DeviceAdQueueRepository : JpaRepository<DeviceAdQueue, DeviceAdQueueId> {
    fun findAllByIdDeviceIdOrderByAddedAtDesc(deviceId: String): List<DeviceAdQueue>

    fun findAllByIdAdId(adId: String): List<DeviceAdQueue>

    @Modifying
    @Query("DELETE FROM DeviceAdQueue q WHERE q.id.deviceId = :deviceId AND q.id.adId = :adId")
    fun deleteOne(@Param("deviceId") deviceId: String, @Param("adId") adId: String): Int

    @Modifying
    @Query("DELETE FROM DeviceAdQueue q WHERE q.id.deviceId = :deviceId")
    fun deleteAllByDeviceId(@Param("deviceId") deviceId: String): Int

    @Modifying
    @Query("DELETE FROM DeviceAdQueue q WHERE q.id.adId = :adId")
    fun deleteAllByAdId(@Param("adId") adId: String): Int
}
