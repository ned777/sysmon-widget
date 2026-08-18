package com.sysmonwidget.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.widget.RemoteViews
import org.json.JSONObject
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

    private fun updateOneWidget(context: Context, manager: AppWidgetManager, id: Int) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val server = prefs.getString("server_address", null)
        val views = RemoteViews(context.packageName, R.layout.widget_sysmon)

        val refreshIntent = Intent(context, SysMonWidgetProvider::class.java).setAction(ACTION_REFRESH)
        val pendingIntent = PendingIntent.getBroadcast(
            context, 0, refreshIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widgetRoot, pendingIntent)

        if (server.isNullOrBlank()) {
            views.setTextViewText(R.id.titleText, context.getString(R.string.widget_no_server))
            views.setTextViewText(R.id.ramText, "")
            views.setTextViewText(R.id.cpuText, "")
            views.setTextViewText(R.id.storageText, "")
            views.setTextViewText(R.id.netText, "")
            views.setTextViewText(R.id.claudeText, "")
            manager.updateAppWidget(id, views)
            return
        }

        val json = StatsClient.fetchStats(server)
        if (json != null) {
            prefs.edit()
                .putString("last_stats_json", json.toString())
                .putLong("last_stats_time", System.currentTimeMillis())
                .apply()
            populateViews(context, views, server, json, stale = false)
        } else {
            val cached = prefs.getString("last_stats_json", null)
            if (cached != null) {
                val lastTime = prefs.getLong("last_stats_time", 0L)
                populateViews(context, views, server, JSONObject(cached), stale = true, lastTimeMillis = lastTime)
            } else {
                views.setTextViewText(
                    R.id.titleText,
                    "$server — ${context.getString(R.string.widget_unreachable)}"
                )
                views.setTextViewText(R.id.ramText, "")
                views.setTextViewText(R.id.cpuText, "")
                views.setTextViewText(R.id.storageText, "")
                views.setTextViewText(R.id.netText, "")
                views.setTextViewText(R.id.claudeText, "")
            }
        }

        manager.updateAppWidget(id, views)
    }

    private fun populateViews(
        context: Context,
        views: RemoteViews,
        server: String,
        json: JSONObject,
        stale: Boolean,
        lastTimeMillis: Long = System.currentTimeMillis()
    ) {
        val title = if (stale) {
            val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(lastTimeMillis))
            "$server — ${context.getString(R.string.widget_unreachable)} (last $timeStr)"
        } else {
            server
        }
        views.setTextViewText(R.id.titleText, title)

        val ram = json.getJSONObject("ram")
        val ramUsedGb = ram.getInt("used_mb") / 1024.0
        val ramTotalGb = ram.getInt("total_mb") / 1024.0
        views.setTextViewText(
            R.id.ramText,
            boldLabel("RAM: ", "%.0fGB out of %.0fGB".format(ramUsedGb, ramTotalGb))
        )

        val cpu = json.getJSONObject("cpu")
        views.setTextViewText(R.id.cpuText, boldLabel("CPU: ", "%.0f%%".format(cpu.getDouble("percent"))))

        val storage = json.getJSONObject("storage")
        views.setTextViewText(
            R.id.storageText,
            boldLabel(
                "Storage: ",
                "%dGB out of %dGB".format(storage.getInt("used_gb"), storage.getInt("total_gb"))
            )
        )

        val net = json.getJSONObject("network")
        views.setTextViewText(
            R.id.netText,
            boldLabel(
                "Network: ",
                "↓${formatRate(net.getLong("rx_bytes_per_sec"))} ↑${formatRate(net.getLong("tx_bytes_per_sec"))}"
            )
        )

        val claude = json.getJSONObject("claude")
        val tokens = claude.getJSONObject("tokens_today")
        val inTok = tokens.getLong("input") + tokens.optLong("cache_read", 0)
        val outTok = tokens.getLong("output")
        val cost = claude.getDouble("est_cost_today_usd")
        views.setTextViewText(
            R.id.claudeText,
            boldLabel(
                "Claude today: ",
                "%s in / %s out · ~$%.2f".format(formatTokens(inTok), formatTokens(outTok), cost)
            )
        )
    }

    private fun boldLabel(label: String, value: String): SpannableString {
        val spannable = SpannableString(label + value)
        spannable.setSpan(StyleSpan(Typeface.BOLD), 0, label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return spannable
    }

    private fun formatRate(bytesPerSec: Long): String {
        if (bytesPerSec < 1024) return "${bytesPerSec}B/s"
        val kb = bytesPerSec / 1024.0
        if (kb < 1024) return "%.0fKB/s".format(kb)
        return "%.1fMB/s".format(kb / 1024.0)
    }

    private fun formatTokens(count: Long): String {
        if (count < 1000) return count.toString()
        val k = count / 1000.0
        if (k < 1000) return "%.0fK".format(k)
        return "%.1fM".format(k / 1000.0)
    }
}
