package com.owldev.adsignage

import android.annotation.SuppressLint
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity

/**
 * AC 11 — AdSignage WebView 래퍼의 단일 액티비티 엔트리 포인트.
 *
 * 역할(의도적으로 최소화 — 재생/스케줄/SSE 로직은 모두 Next.js 플레이어
 * 페이지에 있으며 본 액티비티에는 존재하지 않는다):
 *
 *  - 영구 device_id를 조회한다(최초 부팅 시 생성, AC 12).
 *  - [PlayerUrl]로 플레이어 URL을 만들어 https://stream.owl-dev.me의
 *    /player/{deviceId}를 전체화면 WebView에 로드한다.
 *  - 키오스크 디스플레이에 시스템 바가 보이지 않도록 immersive sticky 모드로
 *    진입하고, 일시적 스와이프 노출 후에도 다시 숨기도록 한다.
 *  - 24/7 냉장고 부착 운영을 위해 화면이 꺼지지 않도록 유지한다.
 *
 * styles.xml의 윈도우 레벨 테마(Theme.AdSignage)가 액티비티 시작 시점에
 * 상태바/내비게이션바를 제거하므로, 본 코드가 실행되기 전부터 콜드 스타트
 * 프레임이 이미 검정 상태가 된다 — 아래 런타임 immersive 호출과 함께
 * 이중 안전장치(belt & braces)로 의도된 구성이다.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 디스플레이가 꺼지지 않도록 유지 — 본 디바이스는 상시 사이니지로 동작한다.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContentView(R.layout.activity_main)
        webView = findViewById(R.id.webview)

        applyImmersiveMode()
        configureWebView(webView)

        // AC 12: 영구 device_id를 조회 또는 생성한 뒤 이를 사용해 플레이어 URL을
        // 구성한다. 최초 호출에서는 생성·저장하고, 이후 호출에서는 동일 값을 반환한다.
        val deviceId = DeviceIdManager.getOrCreateDeviceId(applicationContext)
        val playerBaseUrl = getString(R.string.player_base_url)
        val backendBaseUrl = getString(R.string.backend_base_url)

        // 백엔드 devices 테이블에 자기 device_id 를 등록 (fire-and-forget,
        // 멱등). 앱 lifecycle 당 한 번이면 충분하며, 응답은 무시한다.
        // 분리 배포 — register 는 백엔드 도메인 (stream-backend.owl-dev.me)
        // 으로, WebView 의 player 페이지는 프론트 도메인 (stream.owl-dev.me) 으로.
        DeviceRegistrar.registerAsync(
            baseUrl = backendBaseUrl,
            deviceId = deviceId,
            deviceName = DeviceRegistrar.defaultDeviceName(deviceId),
        )

        val playerUrl = PlayerUrl.forDevice(
            baseUrl = playerBaseUrl,
            deviceId = deviceId,
        )
        webView.loadUrl(playerUrl)
    }

    /**
     * 액티비티가 포커스를 다시 얻을 때마다 immersive 모드를 재적용한다 —
     * "sticky" 동작에 필요: 사용자가 가장자리에서 스와이프하면 시스템 바가
     * 잠시 나타났다가, OS가 다시 포커스를 돌려주면 우리가 그것을 다시 숨긴다.
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersiveMode()
    }

    override fun onResume() {
        super.onResume()
        // 이중 안전장치: 일부 OEM ROM에서는 OS 알림이 포커스를 잠깐 가졌다
        // 돌려주는 과정에서 onWindowFocusChanged가 발사되지 않은 채 바가 보이는
        // 상태로 남을 수 있다. 따라서 매 resume에서도 다시 숨긴다.
        applyImmersiveMode()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView(wv: WebView) {
        val s: WebSettings = wv.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        // 라운드로빈 루프에서 다음 광고가 사용자 제스처 없이 자동 재생되도록 한다.
        s.mediaPlaybackRequiresUserGesture = false
        s.cacheMode = WebSettings.LOAD_DEFAULT
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        // 혼합 콘텐츠는 차단 — 플레이어는 HTTPS 전용이다.
        s.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        s.allowFileAccess = false
        s.allowContentAccess = false

        wv.setBackgroundColor(android.graphics.Color.BLACK)
        wv.webViewClient = WebViewClient()

        // HTML5 <video>가 자체 전체화면 경로를 요청할 수 있도록 WebChromeClient가
        // 필요하다(플레이어 페이지가 라운드로빈 중 <video> 태그에서
        // `requestFullscreen()`을 호출함) — chrome 클라이언트가 없으면
        // 조용히 무시(no-op)된다.
        wv.webChromeClient = WebChromeClient()
    }

    /**
     * 상태바와 내비게이션바를 숨기고, 사용자가 의도적으로 가장자리 스와이프를
     * 할 때까지 계속 숨겨진 상태로 유지한다 — 즉 immersive sticky 모드.
     *
     * 두 가지 코드 경로:
     *   - API 30+에서는 [WindowInsetsController]와
     *     BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE를 사용한다(레거시
     *     SYSTEM_UI_FLAG_IMMERSIVE_STICKY의 현대적 동치).
     *   - API 24–29에서는 해당 릴리스에서 sticky immersive를 만들어주는
     *     레거시 `systemUiVisibility` 플래그 조합으로 폴백한다.
     *
     * app/build.gradle.kts의 minSdk가 24이므로 두 분기 모두 컴파일되어야 한다.
     */
    @Suppress("DEPRECATION")
    private fun applyImmersiveMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let { c ->
                c.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                c.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                )
        }
    }

}
