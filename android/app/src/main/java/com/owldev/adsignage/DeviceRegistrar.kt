package com.owldev.adsignage

import android.os.Build
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlin.concurrent.thread

/**
 * 광고판 디바이스가 부팅 시 자기 device_id 를 백엔드에 등록하기 위한
 * 경량 fire-and-forget 헬퍼.
 *
 * 흐름:
 *   - MainActivity onCreate 후 한 번 호출 (앱 lifecycle 당 1회로 충분).
 *   - 별도 스레드에서 단일 POST 요청을 던지고 응답은 무시.
 *   - 네트워크/서버 실패는 로그로만 남기고 앱 진행을 막지 않음 — 등록은
 *     멱등이므로 다음 부팅 또는 다음 hot start 에서 재시도된다.
 *
 * 라이브러리 의존성 0 — 안드로이드 표준 라이브러리만 사용.
 *
 * 백엔드 계약(SecurityConfig 에 permitAll):
 *   POST {base}/api/devices/register
 *     body: { "deviceId": "<uuid>", "deviceName": "<라벨>" }
 *     -> 201 Created
 */
object DeviceRegistrar {

    private const val TAG = "DeviceRegistrar"
    private const val PATH = "/api/devices/register"
    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 8_000

    /**
     * [baseUrl] 의 백엔드에 [deviceId] 와 [deviceName] 을 신고한다.
     * [baseUrl] 은 strings.xml 의 player_base_url 과 동일한 도메인을
     * 가리키며 — 프론트 도메인이 nginx 에서 `/api/<...>` 를 백엔드로 프록시한다.
     *
     * 호출이 실패해도 throw 하지 않는다 — fire-and-forget.
     */
    fun registerAsync(baseUrl: String, deviceId: String, deviceName: String) {
        val safeBase = baseUrl.trimEnd('/')
        val url = URL("$safeBase$PATH")
        val payload = JSONObject().apply {
            put("deviceId", deviceId)
            put("deviceName", deviceName)
        }.toString()

        thread(start = true, isDaemon = true, name = "device-registrar") {
            try {
                val conn = url.openConnection() as HttpURLConnection
                if (conn is HttpsURLConnection) {
                    // 시스템 기본 TrustManager 사용 (Let's Encrypt 인증서 자동 신뢰).
                }
                try {
                    conn.requestMethod = "POST"
                    conn.connectTimeout = CONNECT_TIMEOUT_MS
                    conn.readTimeout = READ_TIMEOUT_MS
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    conn.setRequestProperty("Accept", "application/json")
                    conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                    val code = conn.responseCode
                    Log.i(TAG, "register POST $url -> HTTP $code  body=$payload")
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                Log.w(TAG, "register POST failed (will retry on next boot): ${e.message}")
            }
        }
    }

    /** 디바이스 모델 + Android 버전을 라벨로 — 어드민에서 식별 보조. */
    fun defaultDeviceName(deviceId: String): String =
        "${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}) · ${deviceId.take(8)}"
}
