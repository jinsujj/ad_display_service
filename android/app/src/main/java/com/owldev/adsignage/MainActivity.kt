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
 * AC 11 — Single-activity entry point for the AdSignage WebView wrapper.
 *
 * Responsibilities (intentionally minimal — all playback / schedule / SSE
 * logic lives in the Next.js player page, not here):
 *
 *  - Resolve the persistent device_id (generated on first boot, AC 12).
 *  - Build the player URL via [PlayerUrl] and load it in a fullscreen
 *    WebView at /player/{deviceId} on https://stream.owl-dev.me.
 *  - Enter immersive sticky mode so the kiosk display has no system bars
 *    and re-hides them after any transient swipe-in.
 *  - Keep the screen on for 24/7 fridge-mounted operation.
 *
 * The window-level theme (Theme.AdSignage in styles.xml) removes the
 * status / nav bars at activity-launch time so the cold-start frame is
 * already black before this code even runs — that is intentional belt &
 * braces with the runtime immersive call below.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep the display on — this device runs as continuous signage.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContentView(R.layout.activity_main)
        webView = findViewById(R.id.webview)

        applyImmersiveMode()
        configureWebView(webView)

        // AC 12: get-or-create the persistent device_id and use it to build
        // the player URL. First call generates and stores; later calls return
        // the same value.
        val deviceId = DeviceIdManager.getOrCreateDeviceId(applicationContext)
        val playerUrl = PlayerUrl.forDevice(
            baseUrl = getString(R.string.player_base_url),
            deviceId = deviceId
        )
        webView.loadUrl(playerUrl)
    }

    /**
     * Re-apply immersive mode whenever the activity regains focus — required
     * for "sticky" behaviour: when the user swipes from an edge the system
     * bars appear briefly, then the OS hands focus back and we re-hide them.
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersiveMode()
    }

    override fun onResume() {
        super.onResume()
        // Belt & braces: an OS notification popping into focus and back can
        // leave the bars visible without firing onWindowFocusChanged on
        // some OEM ROMs. Re-hide on every resume too.
        applyImmersiveMode()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView(wv: WebView) {
        val s: WebSettings = wv.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        // Auto-play the next ad in the round-robin loop without a user gesture.
        s.mediaPlaybackRequiresUserGesture = false
        s.cacheMode = WebSettings.LOAD_DEFAULT
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        // Mixed content is locked down — the player is HTTPS-only.
        s.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        s.allowFileAccess = false
        s.allowContentAccess = false

        wv.setBackgroundColor(android.graphics.Color.BLACK)
        wv.webViewClient = WebViewClient()

        // WebChromeClient is needed so HTML5 <video> can request its own
        // fullscreen path (the player page calls `requestFullscreen()` on the
        // <video> tag during round-robin) — without a chrome client this is
        // a silent no-op.
        wv.webChromeClient = WebChromeClient()
    }

    /**
     * Hide the status bar + navigation bar and keep them hidden until a
     * deliberate edge-swipe — i.e. immersive sticky mode.
     *
     * Two code paths:
     *   - API 30+ uses [WindowInsetsController] with
     *     BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE (the modern equivalent of
     *     the legacy SYSTEM_UI_FLAG_IMMERSIVE_STICKY).
     *   - API 24–29 falls back to the legacy `systemUiVisibility` flag
     *     combination that produces sticky immersive on those releases.
     *
     * minSdk in app/build.gradle.kts is 24, so both branches must compile.
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
