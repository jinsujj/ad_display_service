package com.owldev.adsignage

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

/**
 * Manages persistent device identification.
 *
 * On first boot, generates a fresh UUID and stores it in SharedPreferences.
 * On subsequent boots, returns the previously persisted UUID so the device
 * keeps a stable identity across app restarts and reboots.
 *
 * AC 12: Android APK generates UUID device_id on first boot and persists to
 * SharedPreferences.
 */
object DeviceIdManager {

    private const val PREFS_NAME = "ad_signage_prefs"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_REGISTERED_AT = "device_registered_at"

    /**
     * Returns the persistent device_id for this Android device.
     *
     * If no device_id exists yet (first boot), this method:
     *   1. Generates a new random UUID v4 via [UUID.randomUUID]
     *   2. Persists it to SharedPreferences (commit, synchronous)
     *   3. Records the registration timestamp
     *
     * Subsequent calls return the same UUID without regenerating.
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

        // commit() instead of apply() so the value is durable before we
        // hand it back to the caller — the WebView player URL depends on it.
        prefs.edit()
            .putString(KEY_DEVICE_ID, newDeviceId)
            .putLong(KEY_REGISTERED_AT, registeredAt)
            .commit()

        return newDeviceId
    }

    /**
     * Returns the timestamp (epoch millis) when the device_id was first
     * generated, or 0L if no device_id has been generated yet.
     */
    fun getRegisteredAt(context: Context): Long {
        return prefs(context).getLong(KEY_REGISTERED_AT, 0L)
    }

    /**
     * Returns true if a device_id has already been persisted on this device.
     */
    fun hasDeviceId(context: Context): Boolean {
        return prefs(context).contains(KEY_DEVICE_ID)
    }

    /**
     * For testing / factory-reset scenarios only. Not invoked in normal
     * playback flow — once a device is registered it should keep its UUID.
     */
    fun clearForTesting(context: Context) {
        prefs(context).edit().clear().commit()
    }

    private fun prefs(context: Context): SharedPreferences {
        return context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}
