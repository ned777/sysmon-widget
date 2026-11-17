package com.sysmonwidget.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.View
import android.widget.RemoteViews

/**
 * This is the "brain" of the home-screen widget: an AppWidgetProvider. It is NOT
 * an Activity — nothing about it is ever "on screen" by itself. Instead, it's a
 * BroadcastReceiver (a component that wakes up in response to system-wide
 * announcements/"broadcasts") that Android calls into at specific moments: when a
 * widget is first placed on the home screen, when it should refresh, when it's
 * removed, etc. Its whole job is to build a RemoteViews description of what the
 * widget should currently look like, and hand that to the AppWidgetManager to
 * actually draw.
 *
 * This class is declared as a <receiver> in AndroidManifest.xml, which is how
 * Android knows it exists and what broadcasts it cares about.
 */
class SysMonWidgetProvider : AppWidgetProvider() {

    // `companion object` is Kotlin's way of attaching "static" members to a class
    // — things you can reach via SysMonWidgetProvider.refreshAllWidgets(...)
    // without needing an actual instance of the class first.
    companion object {
        // A custom action name for "please refresh now", used both by the tap
        // handler below and whenever other parts of the app want to force an
        // immediate refresh (e.g. right after you add/edit/delete a device).
        // Android action strings are just plain text, but by convention we
        // prefix ours with our own package name so it can never collide with a
        // built-in system action.
        const val ACTION_REFRESH = "com.sysmonwidget.app.ACTION_REFRESH"

        // Like ACTION_REFRESH, but repaints every widget from whatever is
        // ALREADY sitting in DeviceStatsCache instead of making a fresh
        // network call — used when the app itself just fetched new data (on
        // open, or via pull-to-refresh) so the home-screen widget picks up
        // those same numbers immediately instead of waiting for its own next
        // tap. Keeping this separate from ACTION_REFRESH avoids doubling up
        // network requests every time the app refreshes.
        const val ACTION_REPAINT = "com.sysmonwidget.app.ACTION_REPAINT"
        private const val PREFS_NAME = "sysmon"
        private val TEAL = Color.parseColor("#00E0B8")

        /**
         * Sends a broadcast that every widget of ours will receive via
         * onReceive() below, kicking off a refresh of ALL currently-placed
         * SysMon widgets (there could be more than one, e.g. one per monitored
         * computer).
         */
        fun refreshAllWidgets(context: Context) {
            val intent = Intent(context, SysMonWidgetProvider::class.java).setAction(ACTION_REFRESH)
            context.sendBroadcast(intent)
        }

        /**
         * Sends the cache-only repaint broadcast described above. Call this
         * after the APP finishes its own fetch(es) so any placed widgets stay
         * in sync without each one separately hitting the network again.
         */
        fun repaintAllWidgets(context: Context) {
            val intent = Intent(context, SysMonWidgetProvider::class.java).setAction(ACTION_REPAINT)
            context.sendBroadcast(intent)
        }
    }

    /**
     * Called by Android whenever this widget needs updating "normally" — when
     * it's first added to the home screen, or (if updatePeriodMillis were set to
     * something other than 0 in sysmon_widget_info.xml) on a timer. We update
     * every widget id we were given, one by one.
     *
     * `appWidgetIds: IntArray` — could be several ids at once if, say, the phone
     * just rebooted and multiple SysMon widgets all need updating together.
     */
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        // goAsync() tells Android "I know you'd normally kill this receiver the
        // instant onUpdate() returns — please keep me alive a little longer, I'm
        // not done yet". We need this because fetching stats over the network
        // takes real time (up to a few seconds), and BroadcastReceivers are
        // normally expected to finish in milliseconds.
        val pending = goAsync()

        // Do the actual (slow, network-involving) work on a background Thread
        // instead of Android's main/UI thread — blocking the main thread for
        // even a second or two would make the whole phone feel frozen.
        Thread {
            try {
                appWidgetIds.forEach { updateOneWidget(context, appWidgetManager, it) }
            } finally {
                // MUST call finish() eventually after goAsync(), success or not,
                // or Android will eventually flag this receiver as broken for
                // never releasing its extended lifetime.
                pending.finish()
            }
        }.start()
    }

    /**
     * Called for EVERY broadcast this receiver is registered for — both the
     * standard widget-update broadcasts (which Android turns into a call to
     * onUpdate() automatically) and our own custom ACTION_REFRESH broadcast
     * (which does NOT automatically call onUpdate(), so we handle it ourselves
     * right here).
     */
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_REFRESH || intent.action == ACTION_REPAINT) {
            // ACTION_REFRESH fetches fresh data over the network; ACTION_REPAINT
            // just redraws from whatever's already cached (see the companion
            // object above for why the app fires the latter after its own fetches).
            val forceFetch = intent.action == ACTION_REFRESH
            val pending = goAsync()
            Thread {
                try {
                    val manager = AppWidgetManager.getInstance(context)
                    // Ask Android for the ids of every SysMon widget currently
                    // placed anywhere on this phone, then refresh all of them —
                    // neither action comes with specific widget ids attached
                    // the way onUpdate()'s appWidgetIds does.
                    val ids = manager.getAppWidgetIds(ComponentName(context, SysMonWidgetProvider::class.java))
                    if (forceFetch) ids.forEach { showUpdating(context, manager, it) }
                    ids.forEach { updateOneWidget(context, manager, it, forceFetch) }
                } finally {
                    pending.finish()
                }
            }.start()
        } else {
            // Any other broadcast (like the standard APPWIDGET_UPDATE one) should
            // be handled by AppWidgetProvider's own built-in logic, which is what
            // turns it into a call to onUpdate() above. Forgetting this line would
            // silently break the normal update flow.
            super.onReceive(context, intent)
        }
    }

    /**
     * Called when one or more widget instances are removed from the home screen.
     * We clean up the SharedPreferences entries we were keeping just for those
     * widget ids, so we don't slowly accumulate stale data forever for widgets
     * that no longer exist. Note this only clears WIDGET-scoped keys (which
     * device this widget watched, which stats it showed) — the actual fetched
     * stats live in DeviceStatsCache, keyed by device id, and are left alone
     * since the app (or another widget) may still be using that same device.
     */
    // The launcher re-scales the widget when the home-screen grid changes; redraw
    // (from cache, no network fetch) so the border radius follows the new scale.
    override fun onAppWidgetOptionsChanged(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle) {
        val pending = goAsync()
        Thread {
            try {
                updateOneWidget(context, appWidgetManager, appWidgetId, forceFetch = false)
            } finally {
                pending.finish()
            }
        }.start()
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        appWidgetIds.forEach { id ->
            editor.remove("widget_${id}_device_id")
            editor.remove("widget_${id}_stats")
            editor.remove("widget_${id}_stats_version")
        }
        editor.apply()
    }

    /**
     * Does the actual work of refreshing ONE widget: figure out which device it's
     * pointed at, try to fetch fresh stats from it, and build/send the RemoteViews
     * that make it show up correctly on the home screen. This is the heart of the
     * whole app — almost everything else exists to support this one function.
     */
    private fun updateOneWidget(context: Context, manager: AppWidgetManager, id: Int, forceFetch: Boolean = true) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Start building a fresh RemoteViews from our widget's XML layout. Every
        // call to updateOneWidget rebuilds this from scratch — RemoteViews are
        // cheap, disposable "instructions", not something we keep around and
        // mutate over time.
        val views = RemoteViews(context.packageName, R.layout.widget_sysmon)
        WidgetCorners.apply(views, R.id.widgetRoot, manager, id)
        // Launchers often REAPPLY a new RemoteViews onto the existing widget view
        // (same layout id) rather than rebuilding it, so anything showUpdating()
        // changed would stick unless we set it back explicitly here.
        views.setViewVisibility(R.id.refreshLines, View.VISIBLE)
        views.setViewVisibility(R.id.updatingText, View.GONE)

        // Same split as Weather Donkey: tapping anywhere opens the app, tapping
        // the bottom "Tap below to refresh" strip fetches fresh stats instead.
        val openApp = PendingIntent.getActivity(
            context, id,
            Intent(context, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widgetRoot, openApp)
        // FLAG_IMMUTABLE is required on modern Android — it stops other apps from
        // tampering with what this PendingIntent does after we've created it.
        val refresh = PendingIntent.getBroadcast(
            context, 0, Intent(context, SysMonWidgetProvider::class.java).setAction(ACTION_REFRESH),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.refreshArea, refresh)

        val device = resolveDevice(context, prefs, id)
        if (device == null) {
            // This can happen right after the widget is placed but before its
            // configuration screen finished (or if the chosen device was later
            // deleted). Show a friendly placeholder instead of stats.
            views.setTextViewText(R.id.titleText, context.getString(R.string.widget_no_device))
            views.setTextViewText(R.id.ipText, "")
            views.setTextViewText(R.id.updatedText, "")
            bindStats(views, null, emptySet())
            manager.updateAppWidget(id, views)
            return
        }

        // Show the device's IP address (without the :port part) next to the
        // title regardless of whether this particular refresh succeeds — it's
        // useful to see even while offline.
        views.setTextViewText(R.id.ipText, ipOnly(device.address))

        // The actual network call — see DeviceStatsCache.kt / StatsClient.kt.
        // This blocks the current thread for up to a few seconds, which is
        // fine because updateOneWidget is always called from the background
        // Thread started in onUpdate() / onReceive() above, never from the
        // main thread. Routing through DeviceStatsCache (keyed by device id,
        // not by this specific widget) is what keeps the app and every widget
        // pointed at the same device looking at the exact same fetched data,
        // instead of each independently polling and caching on its own.
        //
        // When forceFetch is false (an ACTION_REPAINT), we deliberately skip
        // the network call and just read what's already cached — the app is
        // what just fetched it, we're only here to redraw with those same
        // numbers.
        val result = if (forceFetch) DeviceStatsCache.fetch(context, device) else DeviceStatsCache.read(context, device.id)
        if (result.reachable) {
            views.setTextViewText(R.id.titleText, device.name)
            views.setTextViewText(R.id.updatedText, "Online · Updated ${StatsFormat.timestampString(result.fetchedAt)}")
        } else if (result.json != null) {
            // We have stale-but-real numbers to show — the stats list itself
            // will keep displaying them (StatsRemoteViewsFactory reads from
            // the same DeviceStatsCache), we just need to say clearly that
            // they're old.
            views.setTextViewText(R.id.titleText, device.name)
            views.setTextViewText(
                R.id.updatedText,
                "${context.getString(R.string.widget_unreachable)} — last ${StatsFormat.timestampString(result.fetchedAt)}"
            )
        } else {
            // We've NEVER successfully reached this device — nothing to fall
            // back to, so say so plainly in the title itself.
            views.setTextViewText(
                R.id.titleText,
                "${device.name} — ${context.getString(R.string.widget_unreachable)}"
            )
            views.setTextViewText(R.id.updatedText, "")
        }

        // Hand the finished RemoteViews to Android, which forwards it to the
        // launcher process to actually be drawn on the home screen.
        bindStats(views, result.json, StatsFormat.enabledStatsFor(prefs, id))
        manager.updateAppWidget(id, views)
    }

    /**
     * Looks up which Device this particular widget id was configured to watch
     * (set once, back in WidgetConfigActivity, and saved to prefs).
     */
    private fun resolveDevice(context: Context, prefs: SharedPreferences, id: Int): Device? {
        val deviceId = prefs.getString("widget_${id}_device_id", null) ?: return null
        return DeviceStore.findDevice(context, deviceId)
    }

    /**
     * Fills the fixed RAM / Disk rows and the temp · load · uptime line.
     * A row is hidden when its stat is switched off for this widget or the
     * device's agent doesn't report it (older agents send no CPU/temp/uptime).
     */
    private fun bindStats(views: RemoteViews, json: org.json.JSONObject?, enabled: Set<String>) {
        fun row(key: String) = if (json != null && key in enabled) StatsFormat.rowFor(key, json, true) else null

        bindBarRow(views, row(StatsFormat.KEY_RAM), R.id.ramRow, R.id.ramValue, R.id.ramBarOk, R.id.ramBarWarn, R.id.ramBarHot)
        bindBarRow(views, row(StatsFormat.KEY_STORAGE), R.id.diskRow, R.id.diskValue, R.id.diskBarOk, R.id.diskBarWarn, R.id.diskBarHot)

        val info = SpannableStringBuilder()
        listOf(
            row(StatsFormat.KEY_TEMP)?.let { "Temp" to it.value },
            row(StatsFormat.KEY_LOAD)?.let { "Load" to it.value.substringBefore(" ") },
            row(StatsFormat.KEY_UPTIME)?.let { "Up" to it.value }
        ).filterNotNull().forEach { (label, value) ->
            if (info.isNotEmpty()) info.append("   ")
            val start = info.length
            info.append(label)
            info.setSpan(ForegroundColorSpan(TEAL), start, info.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            info.setSpan(StyleSpan(Typeface.BOLD), start, info.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            info.append(" ").append(value)
        }
        views.setTextViewText(R.id.infoText, info)
        views.setViewVisibility(R.id.infoText, if (info.isEmpty()) View.GONE else View.VISIBLE)
    }

    /** One RAM/Disk line: value text plus exactly one of its three colored bars. */
    private fun bindBarRow(views: RemoteViews, row: StatRow?, rowId: Int, valueId: Int, okId: Int, warnId: Int, hotId: Int) {
        if (row == null) {
            views.setViewVisibility(rowId, View.GONE)
            return
        }
        views.setViewVisibility(rowId, View.VISIBLE)
        views.setTextViewText(valueId, row.value)
        val pct = row.percent ?: 0
        // teal under 75%, yellow 75-89%, red 90%+
        val shown = when {
            pct >= 90 -> hotId
            pct >= 75 -> warnId
            else -> okId
        }
        for (bar in intArrayOf(okId, warnId, hotId)) {
            views.setViewVisibility(bar, if (bar == shown) View.VISIBLE else View.GONE)
            if (bar == shown) views.setProgressBar(bar, 100, pct, false)
        }
    }

    /** Swap the refresh strip for "Updating…" while a tap-triggered fetch runs. */
    private fun showUpdating(context: Context, manager: AppWidgetManager, id: Int) {
        val views = RemoteViews(context.packageName, R.layout.widget_sysmon)
        views.setViewVisibility(R.id.refreshLines, View.INVISIBLE)
        views.setViewVisibility(R.id.updatingText, View.VISIBLE)
        manager.partiallyUpdateAppWidget(id, views)
    }

    // device.address is stored as "ip:port" (e.g. "192.168.1.50:8765") — for
    // display next to the title we only want the ip part, so we cut the string
    // at the first colon and keep everything before it.
    private fun ipOnly(address: String): String = address.substringBefore(":")
}
