package com.sysmonwidget.app

import android.content.Context
import org.json.JSONObject

/**
 * The single place both the widget and the app go to fetch AND read a
 * device's stats, keyed by DEVICE id.
 *
 * Before this existed, the widget cached its last-fetched JSON per WIDGET
 * instance id, and the app kept a completely separate in-memory copy fetched
 * on its own schedule. That meant two widgets pointed at the same device (or
 * the widget and the app being used around the same time) could each fire
 * off their own network request and briefly disagree about a device's
 * numbers. Routing every fetch through here means there is exactly one
 * on-disk record per device — whichever of the widget or the app fetched
 * most recently is what everything else sees.
 */
object DeviceStatsCache {
    private const val PREFS = "sysmon"

    /**
     * A snapshot of what's known about one device: its last successfully
     * fetched stats JSON (or null if we've never once reached it), whether
     * the most recent attempt succeeded, and when that JSON was fetched.
     */
    data class Cached(val json: JSONObject?, val reachable: Boolean, val fetchedAt: Long)

    /**
     * Reads whatever was last saved for this device WITHOUT making a new
     * network call — used to show something immediately (e.g. the instant
     * the app screen opens) while a fresh fetch runs in the background.
     */
    fun read(context: Context, deviceId: String): Cached {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val json = prefs.getString(jsonKey(deviceId), null)?.let {
            try {
                JSONObject(it)
            } catch (e: Exception) {
                null
            }
        }
        return Cached(
            json = json,
            reachable = prefs.getBoolean(reachableKey(deviceId), true),
            fetchedAt = prefs.getLong(timeKey(deviceId), 0L)
        )
    }

    /**
     * Makes the actual network call (this blocks, so only ever call it from
     * a background thread) and writes the result to the shared cache before
     * returning it — success or failure, the cache always reflects the
     * latest attempt. On failure, the previously cached JSON (if any) is
     * kept and returned alongside `reachable = false`, so callers can still
     * show "last known" numbers instead of nothing.
     */
    fun fetch(context: Context, device: Device): Cached {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val json = StatsClient.fetchStats(device.address)
        if (json != null) {
            val now = System.currentTimeMillis()
            prefs.edit()
                .putString(jsonKey(device.id), json.toString())
                .putLong(timeKey(device.id), now)
                .putBoolean(reachableKey(device.id), true)
                .apply()
            return Cached(json, true, now)
        }
        prefs.edit().putBoolean(reachableKey(device.id), false).apply()
        val stale = read(context, device.id)
        return Cached(stale.json, false, stale.fetchedAt)
    }

    /** Drops everything cached for a device — call this when a device is deleted. */
    fun clear(context: Context, deviceId: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(jsonKey(deviceId))
            .remove(timeKey(deviceId))
            .remove(reachableKey(deviceId))
            .apply()
    }

    private fun jsonKey(deviceId: String) = "device_${deviceId}_last_stats_json"
    private fun timeKey(deviceId: String) = "device_${deviceId}_last_stats_time"
    private fun reachableKey(deviceId: String) = "device_${deviceId}_reachable"
}
