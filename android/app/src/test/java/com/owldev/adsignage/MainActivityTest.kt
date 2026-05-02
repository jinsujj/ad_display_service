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
 * AC 11 검증 — Android APK는 https://stream.owl-dev.me/player/{deviceId}를
 * 가리키는 전체화면 WebView를 immersive sticky 모드로 실행한다.
 *
 * 검증 대상 계약:
 *   1. 액티비티가 WebView를 인플레이트하고 키오스크 자동 재생 설정
 *      (JS on, mediaPlaybackRequiresUserGesture off)을 적용한다.
 *   2. 액티비티가 FLAG_KEEP_SCREEN_ON을 설정하여 디스플레이가 절전 모드로
 *      들어가지 않게 한다.
 *   3. WebView는 디바이스의 영구 UUID에 대해 [PlayerUrl]이 만든 URL,
 *      즉 `https://stream.owl-dev.me/player/{uuid}`를 로드한다.
 *   4. 레거시 코드 경로에서 immersive sticky 모드가 활성화된다
 *      (아래에서 Robolectric을 API ≤ 29로 고정해 결정적으로 검증).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29]) // 레거시 systemUiVisibility 분기를 검증하기 위함
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
        // 최초 부팅 경로: 아직 device_id가 없으므로 MainActivity가 새로
        // 생성·영속화한 뒤 /player/{deviceId}를 로드해야 한다.
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

        // Robolectric은 실제로 URL을 가져오지 않으며, shadow API를 통해
        // 마지막 로드만 기록한다.
        val lastLoaded = shadowOf(webView).lastLoadedUrl
        assertEquals(expected, lastLoaded)
        assertTrue(
            "Player URL must point at the canonical HTTPS host",
            lastLoaded.startsWith("https://stream.owl-dev.me/player/")
        )
    }

    @Test
    fun `WebView reuses the persisted device_id on subsequent activity creates`() {
        // 부팅 1 — 새로 생성.
        val firstActivity = Robolectric.buildActivity(MainActivity::class.java)
            .create()
            .start()
            .resume()
            .visible()
            .get()
        val firstUrl = shadowOf(firstActivity.findViewById<WebView>(R.id.webview)).lastLoadedUrl
        firstActivity.finish()

        // 부팅 2 — 동일 UUID를 재사용해야 하므로 URL도 동일해야 한다.
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

        // 사용자가 시스템 바를 스와이프해 들이는 상황(플래그가 지워짐)을 만든 뒤,
        // OS가 다시 우리에게 포커스를 돌려주는 시나리오를 시뮬레이션한다.
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
