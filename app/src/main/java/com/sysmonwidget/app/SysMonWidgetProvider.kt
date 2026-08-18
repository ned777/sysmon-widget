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

class SysMonWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_REFRESH = "com.sysmonwidget.app.ACTION_REFRESH"
        private const val PREFS_NAME = "sysmon"

        fun refreshAllWidgets(context: Context) {
            val intent = Intent(context, SysMonWidgetProvider::class.java).setAction(ACTION_REFRESH)
            context.sendBroadcast(intent)
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val pending = goAsync()
        Thread {
            try {
                appWidgetIds.forEach { updateOneWidget(context, appWidgetManager, it) }
            } finally {
                pending.finish()
            }
        }.start()
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_REFRESH) {
            val pending = goAsync()
            Thread {
                try {
                    val manager = AppWidgetManager.getInstance(context)
                    val ids = manager.getAppWidgetIds(ComponentName(context, SysMonWidgetProvider::class.java))
                    ids.forEach { updateOneWidget(context, manager, it) }
                } finally {
                    pending.finish()
                }
            }.start()
        } else {
            super.onReceive(context, intent)
        }
    }

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

    private fun updateOneWidget(context: Context, manager: AppWidgetManager, id: Int) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val views = RemoteViews(context.packageName, R.layout.widget_sysmon)
        attachListAdapter(context, views, id)

        val refreshIntent = Intent(context, SysMonWidgetProvider::class.java).setAction(ACTION_REFRESH)
        val pendingIntent = PendingIntent.getBroadcast(
            context, 0, refreshIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widgetRoot, pendingIntent)

        val device = resolveDevice(context, prefs, id)
        if (device == null) {
            views.setTextViewText(R.id.titleText, context.getString(R.string.widget_no_device))
            views.setTextViewText(R.id.updatedText, "")
            manager.updateAppWidget(id, views)
            manager.notifyAppWidgetViewDataChanged(id, R.id.statsList)
            return
        }

        val json = StatsClient.fetchStats(device.address)
        if (json != null) {
            prefs.edit()
                .putString("widget_${id}_last_stats_json", json.toString())
                .putLong("widget_${id}_last_stats_time", System.currentTimeMillis())
                .putBoolean("widget_${id}_reachable", true)
                .apply()
            views.setTextViewText(R.id.titleText, device.name)
            views.setTextViewText(R.id.updatedText, "Updated ${timeString(System.currentTimeMillis())}")
        } else {
            prefs.edit().putBoolean("widget_${id}_reachable", false).apply()
            val cached = prefs.getString("widget_${id}_last_stats_json", null)
            val lastTime = prefs.getLong("widget_${id}_last_stats_time", 0L)
            if (cached != null) {
                views.setTextViewText(R.id.titleText, device.name)
                views.setTextViewText(
                    R.id.updatedText,
                    "${context.getString(R.string.widget_unreachable)} — last ${timeString(lastTime)}"
                )
            } else {
                views.setTextViewText(
                    R.id.titleText,
                    "${device.name} — ${context.getString(R.string.widget_unreachable)}"
                )
                views.setTextViewText(R.id.updatedText, "")
            }
        }

        manager.updateAppWidget(id, views)
        manager.notifyAppWidgetViewDataChanged(id, R.id.statsList)
    }

    private fun resolveDevice(context: Context, prefs: SharedPreferences, id: Int): Device? {
        val deviceId = prefs.getString("widget_${id}_device_id", null) ?: return null
        return DeviceStore.findDevice(context, deviceId)
    }

    private fun attachListAdapter(context: Context, views: RemoteViews, id: Int) {
        val serviceIntent = Intent(context, SysMonRemoteViewsService::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
        }
        views.setRemoteAdapter(R.id.statsList, serviceIntent)
    }

    private fun timeString(millis: Long): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(millis))
}
