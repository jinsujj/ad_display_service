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
 * Verifies AC 13: Android APK auto-starts on BOOT_COMPLETED.
 *
 * The contract under test:
 *   1. When the OS broadcasts BOOT_COMPLETED, BootReceiver schedules a
 *      MainActivity launch with FLAG_ACTIVITY_NEW_TASK.
 *   2. The receiver also responds to OEM quick-boot variants commonly
 *      shipped on cheap fridge-mounted Android boxes.
 *   3. Unrelated broadcasts are ignored (no activity launch, no crash).
 *   4. The persistent device_id (AC 12) is pre-warmed at boot so the
 *      WebView player URL is ready as soon as MainActivity starts.
 */
@RunWith(RobolectricTestRunner::class)
class BootReceiverTest {

    private lateinit var context: Context
    private lateinit var app: Application

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        app = context.applicationContext as Application
        // Reset any leftover state from prior tests in this JVM run.
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
        // Mandatory when starting Activities from a non-Activity context —
        // without it Android throws AndroidRuntimeException at runtime.
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
        // These OEM-specific actions are common on the kind of cheap
        // Android boxes restaurants tend to mount in liquor fridges.
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
        // Defense in depth: even if the receiver got registered for an
        // intent we don't expect, it must not cold-start the WebView.
        deliverBootIntent("android.intent.action.SOMETHING_ELSE")

        val started = shadowOf(app).peekNextStartedActivity()
        assertNull(
            "Unrelated broadcasts must not trigger an activity launch",
            started
        )
    }

    @Test
    fun `null action is ignored gracefully`() {
        // We invoke onReceive directly with an action-less Intent to make
        // sure the receiver doesn't NPE on edge-case broadcasts.
        val intent = Intent() // no action set
        BootReceiver().onReceive(app, intent)

        val started = shadowOf(app).peekNextStartedActivity()
        assertNull("Action-less Intent must not launch any activity", started)
    }

    @Test
    fun `BOOT_COMPLETED pre-warms the persistent device_id`() {
        assertEquals(false, DeviceIdManager.hasDeviceId(context))

        deliverBootIntent(Intent.ACTION_BOOT_COMPLETED)

        // After boot, device_id should be persisted so MainActivity's
        // WebView load doesn't have to wait for the first commit().
        assertTrue(
            "BootReceiver must persist device_id as part of auto-start",
            DeviceIdManager.hasDeviceId(context)
        )
    }

    @Test
    fun `repeated boots reuse the same device_id`() {
        deliverBootIntent(Intent.ACTION_BOOT_COMPLETED)
        val first = DeviceIdManager.getOrCreateDeviceId(context)

        // Simulate a power cycle — receiver fires again, but the persisted
        // UUID must survive (this is the AC 12 invariant exercised through
        // the AC 13 boot flow).
        deliverBootIntent(Intent.ACTION_BOOT_COMPLETED)
        val second = DeviceIdManager.getOrCreateDeviceId(context)

        assertEquals(first, second)
        assertNotEquals("", first)
    }

    @Test
    fun `BOOT_ACTIONS constant matches manifest contract`() {
        // If someone adds a new <action> to the manifest's intent-filter
        // they must also add it to BOOT_ACTIONS — this test guards the
        // pairing so the runtime check in onReceive stays in sync.
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
