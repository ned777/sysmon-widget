package com.sysmonwidget.app

import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import org.json.JSONObject

class SysMonRemoteViewsService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        return StatsRemoteViewsFactory(applicationContext)
    }
}

class StatsRemoteViewsFactory(private val context: Context) : RemoteViewsService.RemoteViewsFactory {

    private var rows: List<CharSequence> = emptyList()

    override fun onCreate() {}

    override fun onDataSetChanged() {
        val prefs = context.getSharedPreferences("sysmon", Context.MODE_PRIVATE)
        val cached = prefs.getString("last_stats_json", null)
        rows = if (cached != null) {
            try {
                StatsFormat.buildStatRows(JSONObject(cached))
            } catch (e: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }
    }

    override fun onDestroy() {
        rows = emptyList()
    }

    override fun getCount(): Int = rows.size

    override fun getViewAt(position: Int): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_stat_item)
        views.setTextViewText(R.id.statText, rows[position])
        return views
    }

    override fun getLoadingView(): RemoteViews? = null
    override fun getViewTypeCount(): Int = 1
    override fun getItemId(position: Int): Long = position.toLong()
    override fun hasStableIds(): Boolean = true
}
