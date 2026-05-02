package com.owldev.adsignage

import android.content.Context
import android.view.View
import android.view.WindowManager
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Verifies AC 11 — Android APK launches a fullscreen WebView in immersive
 * sticky mode pointing at https://stream.owl-dev.me/player/{deviceId}.
 *
 * The contract under test:
 *   1. The activity inflates a WebView and configures it for kiosk
 *      autoplay (JS on, mediaPlaybackRequiresUserGesture off).
 *   2. The activity sets FLAG_KEEP_SCREEN_ON so the display never sleeps.
 *   3. The WebView loads the URL produced by [PlayerUrl] for the device's
 *      persistent UUID — i.e. `https://stream.owl-dev.me/player/{uuid}`.
 *   4. Immersive sticky mode is active on the legacy code path
 *      (Robolectric pins us to API ≤ 29 below so we exercise it
 *      deterministically).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29]) // exercise the legacy systemUiVisibility branch
class MainActivityTest {

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
    fun `onCreate inflates a WebView`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java)
            .create()
            .start()
            .resume()
            .visible()
            .get()

        val webView = activity.findViewById<WebView>(R.id.webview)
        assertNotNull("WebView must be inflated by activity_main.xml", webView)
    }

    @Test
    fun `WebView is configured for kiosk autoplay`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java)
            .create()
            .get()
        val webView = activity.findViewById<WebView>(R.id.webview)
        val settings = webView.settings

        assertTrue("JavaScript must be enabled for the player page", settings.javaScriptEnabled)
        assertTrue("DOM storage required for SSE / playlist state", settings.domStorageEnabled)
        assertEquals(
            "Auto-advance round-robin must not require a user gesture",
            false,
            settings.mediaPlaybackRequiresUserGesture
        )
    }

    @Test
    fun `FLAG_KEEP_SCREEN_ON is set so the kiosk display never sleeps`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java)
            .create()
            .get()

        val flags = activity.window.attributes.flags
        assertTrue(
            "FLAG_KEEP_SCREEN_ON must be applied for 24/7 signage",
            flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0
        )
    }

    @Test
    fun `WebView loads the canonical player URL for the persistent device_id`() {
        // First-boot path: no device_id yet, MainActivity should generate one,
        // persist it, and load /player/{deviceId}.
        val activity = Robolectric.buildActivity(MainActivity::class.java)
            .create()
            .start()
            .resume()
            .visible()
            .get()

        val webView = activity.findViewById<WebView>(R.id.webview)
        val deviceId = DeviceIdManager.getOrCreateDeviceId(context)
        val expected = PlayerUrl.forDevice(
            baseUrl = context.getString(R.string.player_base_url),
            deviceId = deviceId
        )

        // Robolectric doesn't actually fetch the URL; it records the last
        // load via the shadow API.
        val lastLoaded = shadowOf(webView).lastLoadedUrl
        assertEquals(expected, lastLoaded)
        assertTrue(
            "Player URL must point at the canonical HTTPS host",
            lastLoaded.startsWith("https://stream.owl-dev.me/player/")
        )
    }

    @Test
    fun `WebView reuses the persisted device_id on subsequent activity creates`() {
        // Boot 1 — generate.
        val firstActivity = Robolectric.buildActivity(MainActivity::class.java)
            .create()
            .start()
            .resume()
            .visible()
            .get()
        val firstUrl = shadowOf(firstActivity.findViewById<WebView>(R.id.webview)).lastLoadedUrl
        firstActivity.finish()

        // Boot 2 — must reuse same UUID, hence same URL.
        val secondActivity = Robolectric.buildActivity(MainActivity::class.java)
            .create()
            .start()
            .resume()
            .visible()
            .get()
        val secondUrl = shadowOf(secondActivity.findViewById<WebView>(R.id.webview)).lastLoadedUrl

        assertEquals(
            "Same device must produce the same /player/{deviceId} URL across launches",
            firstUrl,
            secondUrl
        )
    }

    @Test
    fun `legacy immersive sticky flags are applied on API below 30`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java)
            .create()
            .start()
            .resume()
            .visible()
            .get()

        @Suppress("DEPRECATION")
        val visibility = activity.window.decorView.systemUiVisibility

        @Suppress("DEPRECATION")
        val expectedMask = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
            )

        assertEquals(
            "Immersive sticky + hide-navigation + fullscreen must all be set",
            expectedMask,
            visibility and expectedMask
        )
    }

    @Test
    fun `regaining window focus re-applies immersive mode`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java)
            .create()
            .start()
            .resume()
            .visible()
            .get()

        // Simulate the user swiping in the system bars (which clears flags),
        // then the OS handing focus back to us.
        @Suppress("DEPRECATION")
        activity.window.decorView.systemUiVisibility = 0
        activity.onWindowFocusChanged(true)

        @Suppress("DEPRECATION")
        val visibility = activity.window.decorView.systemUiVisibility

        @Suppress("DEPRECATION")
        assertTrue(
            "After re-gaining focus, IMMERSIVE_STICKY must be re-asserted",
            visibility and View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY != 0
        )
    }
}
