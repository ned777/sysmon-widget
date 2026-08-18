package com.sysmonwidget.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.widget.RemoteViews
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
        private const val PREFS_NAME = "sysmon"

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
        if (intent.action == ACTION_REFRESH) {
            val pending = goAsync()
            Thread {
                try {
                    val manager = AppWidgetManager.getInstance(context)
                    // Ask Android for the ids of every SysMon widget currently
                    // placed anywhere on this phone, then refresh all of them —
                    // ACTION_REFRESH doesn't come with specific widget ids
                    // attached the way onUpdate()'s appWidgetIds does.
                    val ids = manager.getAppWidgetIds(ComponentName(context, SysMonWidgetProvider::class.java))
                    ids.forEach { updateOneWidget(context, manager, it) }
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
     * that no longer exist.
     */
    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        appWidgetIds.forEach { id ->
            editor.remove("widget_${id}_device_id")
            editor.remove("widget_${id}_last_stats_json")
            editor.remove("widget_${id}_last_stats_time")
            editor.remove("widget_${id}_reachable")
            editor.remove("widget_${id}_stats")
        }
        editor.apply()
    }

    /**
     * Does the actual work of refreshing ONE widget: figure out which device it's
     * pointed at, try to fetch fresh stats from it, and build/send the RemoteViews
     * that make it show up correctly on the home screen. This is the heart of the
     * whole app — almost everything else exists to support this one function.
     */
    private fun updateOneWidget(context: Context, manager: AppWidgetManager, id: Int) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Start building a fresh RemoteViews from our widget's XML layout. Every
        // call to updateOneWidget rebuilds this from scratch — RemoteViews are
        // cheap, disposable "instructions", not something we keep around and
        // mutate over time.
        val views = RemoteViews(context.packageName, R.layout.widget_sysmon)

        // Wires up the scrolling stats list to our RemoteViewsService factory
        // (see SysMonRemoteViewsService.kt) — see that file for why a widget list
        // needs this extra indirection instead of a normal adapter.
        attachListAdapter(context, views, id)

        // Since updatePeriodMillis is 0 (no automatic timer — see
        // sysmon_widget_info.xml), the ONLY way this widget ever refreshes is
        // when the user taps it. We make the entire widget root view clickable,
        // and tapping it fires our own ACTION_REFRESH broadcast right back at
        // this same receiver, which is what actually triggers a new fetch.
        val refreshIntent = Intent(context, SysMonWidgetProvider::class.java).setAction(ACTION_REFRESH)
        val pendingIntent = PendingIntent.getBroadcast(
            context, 0, refreshIntent,
            // FLAG_IMMUTABLE is required on modern Android for security reasons —
            // it stops other apps from tampering with what this PendingIntent
            // actually does after we've created it. FLAG_UPDATE_CURRENT means "if
            // a matching PendingIntent already exists, just refresh its extras
            // instead of creating a brand new one".
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widgetRoot, pendingIntent)

        // Which device (if any) has this specific widget been configured to
        // watch? Each widget instance can point at a different device.
        val device = resolveDevice(context, prefs, id)
        if (device == null) {
            // This can happen right after the widget is placed but before its
            // configuration screen finished (or if the chosen device was later
            // deleted). Show a friendly placeholder instead of stats.
            views.setTextViewText(R.id.titleText, context.getString(R.string.widget_no_device))
            views.setTextViewText(R.id.ipText, "")
            views.setTextViewText(R.id.updatedText, "")
            manager.updateAppWidget(id, views)
            manager.notifyAppWidgetViewDataChanged(id, R.id.statsList)
            return
        }

        // Show the device's IP address (without the :port part) next to the
        // title regardless of whether this particular refresh succeeds — it's
        // useful to see even while offline.
        views.setTextViewText(R.id.ipText, ipOnly(device.address))

        // The actual network call — see StatsClient.kt. This blocks the current
        // thread for up to a few seconds, which is fine because updateOneWidget
        // is always called from the background Thread started in onUpdate() /
        // onReceive() above, never from the main thread.
        val json = StatsClient.fetchStats(device.address)
        if (json != null) {
            // Success: remember these stats (and the time we got them) so that if
            // the device goes offline later, we can still show "last known"
            // numbers instead of nothing at all.
            prefs.edit()
                .putString("widget_${id}_last_stats_json", json.toString())
                .putLong("widget_${id}_last_stats_time", System.currentTimeMillis())
                .putBoolean("widget_${id}_reachable", true)
                .apply()
            views.setTextViewText(R.id.titleText, device.name)
            views.setTextViewText(R.id.updatedText, "Updated ${timeString(System.currentTimeMillis())}")
        } else {
            // Failure: mark the device unreachable, but try to fall back to
            // whatever we last successfully fetched rather than showing nothing.
            prefs.edit().putBoolean("widget_${id}_reachable", false).apply()
            val cached = prefs.getString("widget_${id}_last_stats_json", null)
            val lastTime = prefs.getLong("widget_${id}_last_stats_time", 0L)
            if (cached != null) {
                // We have stale-but-real numbers to show — the stats list itself
                // will keep displaying them (StatsRemoteViewsFactory reads this
                // same cached JSON), we just need to say clearly that they're old.
                views.setTextViewText(R.id.titleText, device.name)
                views.setTextViewText(
                    R.id.updatedText,
                    "${context.getString(R.string.widget_unreachable)} — last ${timeString(lastTime)}"
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
        }

        // Hand the finished RemoteViews to Android, which forwards it to the
        // launcher process to actually be drawn on the home screen.
        manager.updateAppWidget(id, views)

        // Separately tell Android the LIST data might have changed too — this is
        // what makes StatsRemoteViewsFactory.onDataSetChanged() run again and
        // rebuild the row list from what we just saved to prefs above.
        // updateAppWidget() alone does not automatically refresh list contents.
        manager.notifyAppWidgetViewDataChanged(id, R.id.statsList)
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
     * Points the widget's scrolling list at our RemoteViewsService, so the
     * launcher knows which factory to call into for rows. We have to package up
     * an Intent carrying which widget id this is for, and (a slightly odd but
     * required Android quirk) give the Intent a unique `data` Uri — without a
     * unique Uri, widgets that are otherwise configured identically can end up
     * incorrectly sharing the exact same cached list data.
     */
    private fun attachListAdapter(context: Context, views: RemoteViews, id: Int) {
        val serviceIntent = Intent(context, SysMonRemoteViewsService::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
        }
        views.setRemoteAdapter(R.id.statsList, serviceIntent)
    }

    // Formats a timestamp as a plain "HH:mm" 24-hour clock string, e.g. "14:32",
    // using the device's own locale/settings for how that's displayed.
    private fun timeString(millis: Long): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(millis))

    // device.address is stored as "ip:port" (e.g. "192.168.1.50:8765") — for
    // display next to the title we only want the ip part, so we cut the string
    // at the first colon and keep everything before it.
    private fun ipOnly(address: String): String = address.substringBefore(":")
}
