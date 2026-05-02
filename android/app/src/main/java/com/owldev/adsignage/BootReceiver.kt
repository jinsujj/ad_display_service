package com.owldev.adsignage

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * AC 13 — Android APK가 BOOT_COMPLETED 시 자동 시작된다.
 *
 * 디바이스 부팅이 끝나면 사이니지 플레이어를 자동 실행하여, 냉장고 부착형
 * 유닛이 매 전원 사이클(예약 재부팅, 영업시간 전원 스위치, 누전 차단 등)
 * 이후에도 운영자 개입 없이 스스로 재생을 재개하도록 한다.
 *
 * 수용하는 부팅 인텐트(AndroidManifest.xml에 선언됨):
 *   - android.intent.action.BOOT_COMPLETED            (기본 Android)
 *   - android.intent.action.QUICKBOOT_POWERON         (Xiaomi / 일반)
 *   - com.htc.intent.action.QUICKBOOT_POWERON         (HTC)
 *   - android.intent.action.REBOOT                    (일부 커스텀 ROM)
 *
 * 또한 영구 device_id(AC 12)를 미리 준비해 두어, MainActivity의 WebView가
 * 플레이어 페이지 로드를 시작하기 전에 UUID가 준비되도록 한다 — 첫 프레임에서
 * 디스크 읽기를 추가로 발생시키지 않기 위함.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (!isBootAction(action)) {
            Log.w(TAG, "Ignoring non-boot intent: $action")
            return
        }

        Log.i(TAG, "Boot intent received: $action — launching player")

        // 부팅 직후 가능한 한 빨리 device_id가 영속화되도록 보장한다.
        // BroadcastReceiver에서 호출해도 안전: getOrCreateDeviceId는
        // 동기화(synchronized)되어 있으며 내부적으로 commit()(동기) 사용.
        DeviceIdManager.getOrCreateDeviceId(context.applicationContext)

        launchPlayer(context.applicationContext)
    }

    private fun launchPlayer(appContext: Context) {
        // 비-Activity 컨텍스트(BroadcastReceiver)에서 Activity를 시작할 때
        // FLAG_ACTIVITY_NEW_TASK는 필수다. CLEAR_TOP + SINGLE_TOP은
        // 이후 사용자가 홈 화면에서 다시 실행하더라도 MainActivity 인스턴스가
        // 중복 누적되지 않도록 한다.
        val launch = Intent(appContext, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                    or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    or Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        }

        try {
            appContext.startActivity(launch)
        } catch (t: Throwable) {
            // Android 10+에서는 백그라운드 Activity 시작이 제한된다.
            // 사이니지 디바이스에서는 일반적으로 문제가 되지 않는데, 이유는:
            //   (a) BOOT_COMPLETED는 대부분의 OEM에서 문서화된 예외 케이스,
            //   (b) 본 앱은 HOME으로도 등록되어 있어(manifest 참고),
            //       부팅 완료 직후 시스템이 우리 MainActivity를 런처로
            //       resolve하기 때문.
            // 그래도 QA/데모 셋업 시 `adb logcat`으로 실패가 보이도록
            // 로그는 남긴다.
            Log.e(TAG, "Failed to auto-launch MainActivity from boot receiver", t)
        }
    }

    private fun isBootAction(action: String): Boolean = action in BOOT_ACTIONS

    companion object {
        private const val TAG = "AdSignageBoot"

        /**
         * 본 리시버가 응답하는 부팅 인텐트 목록. AndroidManifest.xml의
         * <intent-filter> 항목과 동기화 상태를 유지한다. 테스트에서 참조하도록 노출.
         */
        val BOOT_ACTIONS: Set<String> = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
            "android.intent.action.REBOOT"
        )
    }
}
