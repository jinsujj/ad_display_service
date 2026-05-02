package com.owldev.adsignage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID

/**
 * AC 12 검증: Android APK가 최초 부팅 시 UUID device_id를 생성하고
 * SharedPreferences에 영속화한다.
 */
@RunWith(RobolectricTestRunner::class)
class DeviceIdManagerTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        DeviceIdManager.clearForTesting(context)
    }

    @After
    fun tearDown() {
        DeviceIdManager.clearForTesting(context)
    }

    @Test
    fun `first boot generates a new device_id`() {
        assertEquals(false, DeviceIdManager.hasDeviceId(context))

        val id = DeviceIdManager.getOrCreateDeviceId(context)

        assertNotNull(id)
        // 유효한 UUID여야 함 — 그렇지 않으면 UUID.fromString이 예외를 던진다.
        UUID.fromString(id)
        assertTrue(DeviceIdManager.hasDeviceId(context))
    }

    @Test
    fun `subsequent boots return the persisted device_id`() {
        val first = DeviceIdManager.getOrCreateDeviceId(context)
        val second = DeviceIdManager.getOrCreateDeviceId(context)
        val third = DeviceIdManager.getOrCreateDeviceId(context)

        assertEquals(first, second)
        assertEquals(first, third)
    }

    @Test
    fun `device_id is persisted across manager calls and survives via SharedPreferences`() {
        val first = DeviceIdManager.getOrCreateDeviceId(context)

        // SharedPreferences를 직접 읽어 프로세스 재시작 상황을 시뮬레이션한다.
        val prefs = context.getSharedPreferences("ad_signage_prefs", Context.MODE_PRIVATE)
        val stored = prefs.getString("device_id", null)

        assertEquals(first, stored)
    }

    @Test
    fun `registered_at timestamp is recorded on first boot`() {
        val before = System.currentTimeMillis()
        DeviceIdManager.getOrCreateDeviceId(context)
        val after = System.currentTimeMillis()

        val registeredAt = DeviceIdManager.getRegisteredAt(context)
        assertTrue("registeredAt $registeredAt should be >= $before", registeredAt >= before)
        assertTrue("registeredAt $registeredAt should be <= $after", registeredAt <= after)
    }

    @Test
    fun `clearing prefs and regenerating produces a different id`() {
        val first = DeviceIdManager.getOrCreateDeviceId(context)
        DeviceIdManager.clearForTesting(context)
        val second = DeviceIdManager.getOrCreateDeviceId(context)

        // 랜덤 UUID — 충돌 확률은 사실상 0에 가깝다.
        assertNotEquals(first, second)
    }
}
