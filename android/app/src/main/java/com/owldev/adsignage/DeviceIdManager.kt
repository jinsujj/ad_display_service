package com.owldev.adsignage

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

/**
 * 영구 디바이스 식별자를 관리한다.
 *
 * 최초 부팅 시 새 UUID를 생성해 SharedPreferences에 저장한다.
 * 이후 부팅에서는 이전에 영속화된 UUID를 반환하여, 앱 재시작 및 재부팅
 * 사이에서도 디바이스가 안정적인 식별자를 유지하도록 한다.
 *
 * AC 12: Android APK가 최초 부팅 시 UUID device_id를 생성하고
 * SharedPreferences에 영속화한다.
 */
object DeviceIdManager {

    private const val PREFS_NAME = "ad_signage_prefs"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_REGISTERED_AT = "device_registered_at"

    /**
     * 본 Android 디바이스의 영구 device_id를 반환한다.
     *
     * 아직 device_id가 없을 경우(최초 부팅) 본 메서드는:
     *   1. [UUID.randomUUID]로 새 랜덤 UUID v4를 생성하고
     *   2. SharedPreferences에 영속화(commit, 동기)하며
     *   3. 등록 타임스탬프를 기록한다.
     *
     * 이후 호출에서는 재생성 없이 동일한 UUID를 반환한다.
     */
    @Synchronized
    fun getOrCreateDeviceId(context: Context): String {
        val prefs = prefs(context)
        val existing = prefs.getString(KEY_DEVICE_ID, null)
        if (existing != null && existing.isNotBlank()) {
            return existing
        }

        val newDeviceId = UUID.randomUUID().toString()
        val registeredAt = System.currentTimeMillis()

        // 호출자에게 값을 넘기기 전에 영속성을 보장하기 위해 apply()가 아닌
        // commit() 사용 — WebView 플레이어 URL이 이 값에 의존한다.
        prefs.edit()
            .putString(KEY_DEVICE_ID, newDeviceId)
            .putLong(KEY_REGISTERED_AT, registeredAt)
            .commit()

        return newDeviceId
    }

    /**
     * device_id가 최초로 생성된 시점의 타임스탬프(epoch millis)를 반환한다.
     * 아직 device_id가 생성되지 않았다면 0L을 반환한다.
     */
    fun getRegisteredAt(context: Context): Long {
        return prefs(context).getLong(KEY_REGISTERED_AT, 0L)
    }

    /**
     * 본 디바이스에 device_id가 이미 영속화되어 있으면 true를 반환한다.
     */
    fun hasDeviceId(context: Context): Boolean {
        return prefs(context).contains(KEY_DEVICE_ID)
    }

    /**
     * 테스트 및 공장 초기화 시나리오 전용. 일반 재생 플로우에서는 호출되지 않는다 —
     * 디바이스가 한 번 등록되면 해당 UUID를 계속 유지해야 한다.
     */
    fun clearForTesting(context: Context) {
        prefs(context).edit().clear().commit()
    }

    private fun prefs(context: Context): SharedPreferences {
        return context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}
