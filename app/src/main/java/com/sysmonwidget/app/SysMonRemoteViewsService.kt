package com.sysmonwidget.app

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import org.json.JSONObject

/**
 * Normal Views (Buttons, TextViews, ListViews...) live inside YOUR app's process.
 * A home-screen widget, though, is drawn by the LAUNCHER app (the home screen
 * itself), not by our app — our app might not even be running. So widgets can't
 * use regular Views directly; instead we describe what to draw using RemoteViews,
 * a limited, serializable "recipe" that Android can ship over to the launcher
 * process and recreate there.
 *
 * A scrolling list inside a widget (our stats list) needs its OWN little factory
 * that the launcher can call into (across process boundaries) whenever it needs
 * one more row drawn. This file is exactly that factory. It's registered as a
 * <service> in AndroidManifest.xml so the OS knows how to reach it.
 */
class SysMonRemoteViewsService : RemoteViewsService() {

    /**
     * The launcher calls this once per widget instance to get a factory object
     * that knows how to build that specific widget's rows. We read which widget
     * this is for out of the Intent's extras (every home-screen widget gets its
     * own numeric ID from Android), and hand back a factory configured for it.
     */
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        val appWidgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID
        )
        return StatsRemoteViewsFactory(applicationContext, appWidgetId)
    }
}

/**
 * This is the actual "adapter" for the widget's stats list — conceptually very
 * similar to DeviceAdapter, but built for RemoteViews (widget rows) instead of
 * normal Views (app screen rows), and living inside a RemoteViewsService instead
 * of an Activity.
 */
class StatsRemoteViewsFactory(
    private val context: Context,
    private val appWidgetId: Int
) : RemoteViewsService.RemoteViewsFactory {

    // The already-built, ready-to-display rows for this widget. We keep them
    // pre-built here rather than computing them fresh on every getViewAt() call,
    // since getCount()/getViewAt() can be called several times in quick
    // succession while the list is drawing.
    private var rows: List<CharSequence> = emptyList()

    // Called once when this factory is first created. We have nothing to set up
    // up front — everything happens in onDataSetChanged() below — but the
    // interface requires us to provide this function even if it does nothing.
    override fun onCreate() {}

    /**
     * Called by the system whenever it's told the data behind this list might
     * have changed (we trigger that ourselves by calling
     * notifyAppWidgetViewDataChanged() over in SysMonWidgetProvider). This is
     * where we actually go read the latest saved stats and turn them into rows.
     */
    override fun onDataSetChanged() {
        val prefs = context.getSharedPreferences("sysmon", Context.MODE_PRIVATE)

        // SysMonWidgetProvider is the one that actually talks to the device over
        // the network and saves the result here — this factory just reads
        // whatever was saved most recently, it never makes network calls itself.
        val cached = prefs.getString("widget_${appWidgetId}_last_stats_json", null)
        val reachable = prefs.getBoolean("widget_${appWidgetId}_reachable", true)
        val statsPref = prefs.getString("widget_${appWidgetId}_stats", null)

        // If the user never customized which stats to show for this widget,
        // default to showing all of them.
        val enabledStats = if (statsPref.isNullOrBlank()) {
            StatsFormat.ALL_KEYS.toSet()
        } else {
            statsPref.split(",").toSet()
        }

        rows = if (cached != null) {
            try {
                StatsFormat.buildStatRows(JSONObject(cached), reachable, enabledStats)
            } catch (e: Exception) {
                // If the saved JSON is ever malformed (shouldn't normally happen,
                // but better safe than crashing the whole home screen), just show
                // no rows rather than taking down the widget.
                emptyList()
            }
        } else {
            emptyList()
        }
    }

    // Called when this factory is no longer needed (e.g. the widget was removed).
    // We drop our reference to the rows so they can be garbage collected.
    override fun onDestroy() {
        rows = emptyList()
    }

    // How many rows the list should have — the launcher calls this to know how
    // far it can scroll / how many times to call getViewAt().
    override fun getCount(): Int = rows.size

    /**
     * Builds ONE row's RemoteViews. This is the widget equivalent of
     * DeviceAdapter.getView() — but notice we can't just reuse an existing View
     * here the way a normal ListView adapter would (there's no `convertView`
     * parameter), because RemoteViews are lightweight, disposable descriptions
     * rather than real View objects living in memory.
     */
    override fun getViewAt(position: Int): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_stat_item)
        views.setTextViewText(R.id.statText, rows[position])
        return views
    }

    // We don't show a placeholder while a row is loading (returning null means
    // "no special loading view, just leave it blank until ready").
    override fun getLoadingView(): RemoteViews? = null

    // All our rows use the exact same layout (widget_stat_item.xml), so there's
    // only one "view type" — this helps Android reuse row layouts efficiently.
    override fun getViewTypeCount(): Int = 1

    // Using the row's position as its id is fine here since our list is always
    // fully rebuilt (not incrementally edited), so there's no risk of an id
    // pointing at the wrong row after a change.
    override fun getItemId(position: Int): Long = position.toLong()

    // Declares that getItemId() gives out ids that reliably identify the same
    // logical row across refreshes, which lets Android animate/scroll more
    // smoothly. True is the simple, correct answer given how getItemId() works
    // here.
    override fun hasStableIds(): Boolean = true
}
