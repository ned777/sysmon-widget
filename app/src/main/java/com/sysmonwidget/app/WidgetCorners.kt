package com.sysmonwidget.app

import android.appwidget.AppWidgetManager
import android.content.res.Resources
import android.os.Build
import android.widget.RemoteViews

/**
 * Draws the widget's rounded border 4dp in from the widget's edges, with a corner
 * radius chosen so the launcher's own rounded-corner mask can never clip it.
 *
 * Why this is needed: launchers clip every widget to their own corner radius. Samsung
 * One UI also lays the widget out bigger and then shrinks it to fit the home-screen
 * grid (shrink factor in the widget options as "hsResizeRatio", e.g. 0.83; its mask
 * radius as "semShapeRadius", 24dp). Our border shrinks with it but the mask doesn't,
 * so an edge-to-edge border got its corners shaved off.
 *
 * The 4dp inset gives breathing room from the mask; the radius is then:
 *  - launcher reports its scale: maskRadius / ratio - inset (+1 rounding slack), so the
 *    border's curve runs parallel to the mask's curve after scaling;
 *  - launcher doesn't report it (Pixel, Nova, ...): maskRadius + 2dp, which together with
 *    the inset stays clear of the mask even if the launcher secretly shrinks to ~80%.
 *
 * RemoteViews can't set a drawable's radius directly, so copies of the background are
 * pre-baked at 1dp steps (widget_background_r20_inset..r36_inset) and the nearest one at least
 * as round as needed is picked (rounder is always safe; less round can be clipped).
 */
object WidgetCorners {
    private const val MIN_RADIUS_DP = 20
    private const val INSET_DP = 4
    private const val UNKNOWN_SCALE_EXTRA_RADIUS_DP = 2
    private val VARIANTS = intArrayOf(
        R.drawable.widget_background_r20_inset,
        R.drawable.widget_background_r21_inset,
        R.drawable.widget_background_r22_inset,
        R.drawable.widget_background_r23_inset,
        R.drawable.widget_background_r24_inset,
        R.drawable.widget_background_r25_inset,
        R.drawable.widget_background_r26_inset,
        R.drawable.widget_background_r27_inset,
        R.drawable.widget_background_r28_inset,
        R.drawable.widget_background_r29_inset,
        R.drawable.widget_background_r30_inset,
        R.drawable.widget_background_r31_inset,
        R.drawable.widget_background_r32_inset,
        R.drawable.widget_background_r33_inset,
        R.drawable.widget_background_r34_inset,
        R.drawable.widget_background_r35_inset,
        R.drawable.widget_background_r36_inset
    )

    /** Extra padding a layout needs so its content keeps its old distance from the (now inset) border. */
    const val BORDER_INSET_DP = INSET_DP

    /** Swap [rootViewId]'s background to the inset border whose radius suits this widget's scaling. */
    fun apply(views: RemoteViews, rootViewId: Int, manager: AppWidgetManager, widgetId: Int) {
        val opts = manager.getAppWidgetOptions(widgetId)
        val maskRadiusDp = opts.getFloat("semShapeRadius", 0f).takeIf { it > 0f } ?: systemRadiusDp()
        val ratio = opts.getFloat("hsResizeRatio", -1f)
        val neededDp = if (ratio in 0.3f..1f) {
            maskRadiusDp / ratio - INSET_DP + 1
        } else {
            maskRadiusDp + UNKNOWN_SCALE_EXTRA_RADIUS_DP
        }
        // Round up (tiny epsilon so 25.0000001 doesn't jump to 26).
        val index = (Math.ceil(neededDp - 0.05).toInt() - MIN_RADIUS_DP).coerceIn(0, VARIANTS.size - 1)
        views.setInt(rootViewId, "setBackgroundResource", VARIANTS[index])
    }

    private fun systemRadiusDp(): Float {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return 20f
        val res = Resources.getSystem()
        return res.getDimension(android.R.dimen.system_app_widget_background_radius) / res.displayMetrics.density
    }
}
