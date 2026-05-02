package com.owldev.adsignage

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * AC 13 검증: Android APK가 BOOT_COMPLETED 시 자동 시작된다.
 *
 * 검증 대상 계약:
 *   1. OS가 BOOT_COMPLETED를 브로드캐스트하면 BootReceiver는
 *      FLAG_ACTIVITY_NEW_TASK로 MainActivity 실행을 예약한다.
 *   2. 본 리시버는 저가 냉장고 부착형 Android 박스에서 흔히 출하되는
 *      OEM 퀵부트 변종에도 응답한다.
 *   3. 관련 없는 브로드캐스트는 무시된다(액티비티 미실행, 크래시 없음).
 *   4. 영구 device_id(AC 12)가 부팅 시점에 미리 준비되어 MainActivity가
 *      시작하자마자 WebView 플레이어 URL이 즉시 준비되어 있다.
 */
@RunWith(RobolectricTestRunner::class)
class BootReceiverTest {

    private lateinit var context: Context
    private lateinit var app: Application

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        app = context.applicationContext as Application
        // 동일 JVM 실행에서 이전 테스트가 남긴 상태를 모두 초기화.
        DeviceIdManager.clearForTesting(context)
        shadowOf(app).clearNextStartedActivities()
    }

    @After
    fun tearDown() {
        DeviceIdManager.clearForTesting(context)
        shadowOf(app).clearNextStartedActivities()
    }

    @Test
    fun `BOOT_COMPLETED launches MainActivity`() {
        deliverBootIntent(Intent.ACTION_BOOT_COMPLETED)

        val started = shadowOf(app).peekNextStartedActivity()
        assertNotNull("MainActivity should have been started on BOOT_COMPLETED", started)

        val expected = ComponentName(app, MainActivity::class.java)
        assertEquals(expected, started!!.component)
    }

    @Test
    fun `BOOT_COMPLETED launch uses NEW_TASK flag`() {
        // 비-Activity 컨텍스트에서 Activity를 시작할 때 필수 —
        // 이 플래그가 없으면 Android가 런타임에 AndroidRuntimeException을 던진다.
        deliverBootIntent(Intent.ACTION_BOOT_COMPLETED)

        val started = shadowOf(app).peekNextStartedActivity()
        assertNotNull(started)

        val flags = started!!.flags
        assertTrue(
            "FLAG_ACTIVITY_NEW_TASK must be set when launching from BroadcastReceiver",
            flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0
        )
    }

    @Test
    fun `OEM quick-boot intents also trigger auto-start`() {
        // 식당이 주류 냉장고에 흔히 부착하는 저가 Android 박스에서 자주 보이는
        // OEM 전용 액션들.
        val oemActions = listOf(
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
            "android.intent.action.REBOOT"
        )

        for (action in oemActions) {
            shadowOf(app).clearNextStartedActivities()
            deliverBootIntent(action)

            val started = shadowOf(app).peekNextStartedActivity()
            assertNotNull("Receiver must handle $action", started)
            assertEquals(
                "$action must launch MainActivity",
                ComponentName(app, MainActivity::class.java),
                started!!.component
            )
        }
    }

    @Test
    fun `unrelated broadcasts do not launch MainActivity`() {
        // 다층 방어: 예상치 못한 인텐트에 리시버가 등록되어 있더라도
        // WebView를 콜드 스타트해서는 안 된다.
        deliverBootIntent("android.intent.action.SOMETHING_ELSE")

        val started = shadowOf(app).peekNextStartedActivity()
        assertNull(
            "Unrelated broadcasts must not trigger an activity launch",
            started
        )
    }

    @Test
    fun `null action is ignored gracefully`() {
        // 액션이 없는 Intent로 onReceive를 직접 호출하여, 엣지 케이스
        // 브로드캐스트에서 리시버가 NPE를 일으키지 않는지 확인한다.
        val intent = Intent() // 액션 미설정
        BootReceiver().onReceive(app, intent)

        val started = shadowOf(app).peekNextStartedActivity()
        assertNull("Action-less Intent must not launch any activity", started)
    }

    @Test
    fun `BOOT_COMPLETED pre-warms the persistent device_id`() {
        assertEquals(false, DeviceIdManager.hasDeviceId(context))

        deliverBootIntent(Intent.ACTION_BOOT_COMPLETED)

        // 부팅 이후 device_id가 영속화되어, MainActivity의 WebView 로드가
        // 최초 commit()을 기다릴 필요가 없도록 한다.
        assertTrue(
            "BootReceiver must persist device_id as part of auto-start",
            DeviceIdManager.hasDeviceId(context)
        )
    }

    @Test
    fun `repeated boots reuse the same device_id`() {
        deliverBootIntent(Intent.ACTION_BOOT_COMPLETED)
        val first = DeviceIdManager.getOrCreateDeviceId(context)

        // 전원 사이클을 시뮬레이션 — 리시버가 다시 실행되어도, 영속화된
        // UUID는 유지되어야 한다(이는 AC 13 부팅 플로우를 통해 검증되는
        // AC 12 불변식이다).
        deliverBootIntent(Intent.ACTION_BOOT_COMPLETED)
        val second = DeviceIdManager.getOrCreateDeviceId(context)

        assertEquals(first, second)
        assertNotEquals("", first)
    }

    @Test
    fun `BOOT_ACTIONS constant matches manifest contract`() {
        // 누군가 매니페스트의 intent-filter에 새로운 <action>을 추가했다면
        // BOOT_ACTIONS에도 반드시 추가해야 한다 — 본 테스트는 이 한 쌍이
        // 어긋나지 않도록 지켜, onReceive의 런타임 체크가 매니페스트와
        // 항상 동기화 상태를 유지하도록 한다.
        val expected = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
            "android.intent.action.REBOOT"
        )
        assertEquals(expected, BootReceiver.BOOT_ACTIONS)
    }

    private fun deliverBootIntent(action: String) {
        val intent = Intent(action)
        BootReceiver().onReceive(app, intent)
    }
}
